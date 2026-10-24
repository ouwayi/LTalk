package cn.ltalk.client;

import cn.ltalk.common.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ChatClient implements AutoCloseable {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED }
    private final Object lifecycle = new Object();
    private final ClientEventListener listener;
    private final Executor callbacks;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(daemonFactory("ltalk-client-deadline-"));
    private final int connectTimeoutMillis;
    private final int loginTimeoutMillis;
    private volatile State state = State.DISCONNECTED;
    private Connection active;
    private boolean disposed;

    public ChatClient(ClientEventListener listener, Executor callbacks) { this(listener, callbacks, 3000, 5000); }

    public ChatClient(ClientEventListener listener, Executor callbacks, int connectTimeoutMillis, int loginTimeoutMillis) {
        if (connectTimeoutMillis < 1 || loginTimeoutMillis < 1) throw new IllegalArgumentException("超时必须为正数");
        this.listener = listener;
        this.callbacks = callbacks;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.loginTimeoutMillis = loginTimeoutMillis;
    }

    public State state() { return state; }

    public void connect(String host, int port, String userId) throws IOException {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("请输入服务端 IP 或主机名");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须为 1～65535");
        String normalized = Validation.userId(userId);
        synchronized (lifecycle) {
            if (disposed) throw new IllegalStateException("客户端已关闭");
            if (state != State.DISCONNECTED) throw new IllegalStateException("请先断开当前连接");
            Connection connection = new Connection(normalized);
            active = connection;
            state = State.CONNECTING;
            emit(connection, () -> listener.onStateChanged(State.CONNECTING, "正在连接并登录…"));
            connection.deadline = deadlines.schedule(() -> terminate(connection, "连接或登录超时，请检查地址后重新连接"),
                    (long) connectTimeoutMillis + loginTimeoutMillis, TimeUnit.MILLISECONDS);
            connection.sender.execute(() -> establish(connection, host.strip(), port));
        }
    }

    private void establish(Connection connection, String host, int port) {
        try {
            connection.socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            connection.socket.setTcpNoDelay(true);
            connection.socket.setSoTimeout(loginTimeoutMillis);
            MessageCodec.write(connection.socket.getOutputStream(), Message.of(MessageType.LOGIN)
                    .requestId(connection.nextId()).userId(connection.userId).build());
            Thread reader = daemonFactory("ltalk-client-reader-").newThread(() -> receive(connection));
            reader.start();
        } catch (IOException | RuntimeException ex) { terminate(connection, "连接失败：" + ex.getMessage()); }
    }

    private void receive(Connection connection) {
        String reason = "服务端已关闭连接，请手动重新连接";
        try {
            MessageCodec.FrameReader reader = new MessageCodec.FrameReader(connection.socket.getInputStream());
            while (!connection.closed.get()) {
                Message message = reader.read();
                if (message == null) break;
                if (!connection.loggedIn) {
                    if (message.type() == MessageType.ERROR) {
                        emit(connection, () -> listener.onError(message));
                        reason = "登录失败：" + message.message();
                        break;
                    }
                    if (message.type() != MessageType.LOGIN_OK || !connection.userId.equals(message.userId()))
                        throw new IOException("服务端未返回有效 LOGIN_OK");
                    synchronized (lifecycle) {
                        if (active != connection || connection.closed.get()) return;
                        connection.loggedIn = true;
                        connection.socket.setSoTimeout(0);
                        connection.deadline.cancel(false);
                        state = State.CONNECTED;
                        emit(connection, () -> listener.onStateChanged(State.CONNECTED, "已连接 · " + connection.userId));
                    }
                } else {
                    dispatch(connection, message);
                }
            }
        } catch (IOException | RuntimeException ex) { reason = "连接中断：" + ex.getMessage() + "，请手动重新连接"; }
        finally { terminate(connection, reason); }
    }

    private void dispatch(Connection connection, Message message) throws IOException {
        switch (message.type()) {
            case GROUP_MESSAGE, PRIVATE_MESSAGE -> {
                if (message.from() == null || message.content() == null || message.serverTime() == null
                        || (message.type() == MessageType.PRIVATE_MESSAGE && message.to() == null))
                    throw new IOException("聊天事件缺少字段");
                emit(connection, () -> {
                    if (message.type() == MessageType.GROUP_MESSAGE) listener.onGroup(message);
                    else listener.onPrivate(message);
                });
            }
            case USER_LIST -> {
                if (message.users() == null || message.revision() == null) throw new IOException("在线列表缺少字段");
                if (message.revision() <= connection.lastRevision) return;
                connection.lastRevision = message.revision();
                emit(connection, () -> listener.onUserList(message));
            }
            case SYSTEM -> emit(connection, () -> listener.onSystem(message));
            case ERROR -> emit(connection, () -> listener.onError(message));
            default -> throw new IOException("服务端发送了不允许的消息类型");
        }
    }

    public void sendGroup(String content) throws IOException { submitChat(null, content); }
    public void sendPrivate(String target, String content) throws IOException {
        if (target == null || !target.matches("[A-Za-z0-9_]{1,20}"))
            throw new ProtocolException("BAD_MESSAGE", "请选择当前在线的私聊对象");
        submitChat(target, content);
    }

    private void submitChat(String target, String content) throws IOException {
        Validation.content(content);
        synchronized (lifecycle) {
            Connection connection = active;
            if (state != State.CONNECTED || connection == null || connection.closed.get())
                throw new IOException("尚未连接，请先连接服务端");
            if (connection.userId.equals(target))
                throw new ProtocolException("SELF_PRIVATE_NOT_ALLOWED", "不能给自己发私聊");
            Message request = Message.of(target == null ? MessageType.GROUP_SEND : MessageType.PRIVATE_SEND)
                    .requestId(connection.nextId()).to(target).content(content).build();
            MessageCodec.encode(request); // Local frame validation does not tear down a healthy connection.
            try {
                connection.sender.execute(() -> {
                    if (connection.closed.get()) return;
                    try { MessageCodec.write(connection.socket.getOutputStream(), request); }
                    catch (IOException ex) { terminate(connection, "消息发送失败：" + ex.getMessage() + "，请重新连接；消息未确认"); }
                });
            } catch (RejectedExecutionException ex) { throw new IOException("发送队列已满或连接已关闭，请稍后重试"); }
        }
    }

    /** Closing TCP also performs normal logout; no network write runs on the calling UI thread. */
    public void disconnect() {
        Connection connection;
        synchronized (lifecycle) { connection = active; }
        if (connection != null) terminate(connection, "已主动断开");
    }

    private void terminate(Connection connection, String reason) {
        if (!connection.closed.compareAndSet(false, true)) return;
        try { connection.socket.close(); } catch (IOException ignored) { }
        if (connection.deadline != null) connection.deadline.cancel(false);
        connection.sender.shutdownNow();
        synchronized (lifecycle) {
            if (active != connection) return;
            state = State.DISCONNECTED;
            emit(connection, () -> listener.onStateChanged(State.DISCONNECTED, reason));
        }
    }

    private void emit(Connection connection, Runnable event) {
        callbacks.execute(() -> {
            synchronized (lifecycle) {
                // Includes callbacks already queued on the EDT when the user starts another connection.
                if (active == connection) event.run();
            }
        });
    }

    @Override public void close() {
        synchronized (lifecycle) { disposed = true; }
        disconnect();
        deadlines.shutdownNow();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> { Thread thread = new Thread(task, prefix + sequence.incrementAndGet()); thread.setDaemon(true); return thread; };
    }

    private static final class Connection {
        final String userId;
        final Socket socket = new Socket();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicLong sequence = new AtomicLong();
        final ExecutorService sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), daemonFactory("ltalk-client-send-"), new ThreadPoolExecutor.AbortPolicy());
        volatile ScheduledFuture<?> deadline;
        boolean loggedIn;
        long lastRevision = -1;
        Connection(String userId) { this.userId = userId; }
        String nextId() { return "r" + sequence.incrementAndGet(); }
    }
}
