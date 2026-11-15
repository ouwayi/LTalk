package cn.ltalk.client;

import cn.ltalk.common.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ChatClient implements AutoCloseable {
    public enum State { DISCONNECTED, CONNECTING, SERVER_CONNECTED, AUTHENTICATING, CONNECTED }
    private final Object lifecycle = new Object();
    private final ClientEventListener listener;
    private final Executor callbacks;
    private final ScheduledExecutorService deadlines = deadlineExecutor();
    private static ScheduledExecutorService deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, daemonFactory("ltalk-client-deadline-"));
        executor.setRemoveOnCancelPolicy(true); return executor;
    }
    private final int connectTimeoutMillis;
    private final int loginTimeoutMillis;
    private final HeartbeatSettings heartbeatSettings;
    private volatile State state = State.DISCONNECTED;
    private Connection active;
    private boolean disposed;

    public ChatClient(ClientEventListener listener, Executor callbacks) { this(listener, callbacks, 3000, 5000); }

    public ChatClient(ClientEventListener listener, Executor callbacks, int connectTimeoutMillis, int loginTimeoutMillis) {
        this(listener, callbacks, connectTimeoutMillis, loginTimeoutMillis, HeartbeatSettings.fromSystemProperties());
    }

    public ChatClient(ClientEventListener listener, Executor callbacks, int connectTimeoutMillis, int loginTimeoutMillis,
                      HeartbeatSettings heartbeatSettings) {
        if (connectTimeoutMillis < 1 || loginTimeoutMillis < 1) throw new IllegalArgumentException("超时必须为正数");
        this.listener = listener;
        this.callbacks = callbacks;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.loginTimeoutMillis = loginTimeoutMillis;
        this.heartbeatSettings = java.util.Objects.requireNonNull(heartbeatSettings);
    }

    public State state() { return state; }

    public void connect(String host, int port, String userId) throws IOException {
        connect(host, port, userId, "");
    }

    public void connect(String host, int port, String userId, String password) throws IOException {
        startConnection(host, port, Validation.userId(userId), password);
    }
    public void connectServer(String host, int port) throws IOException {
        startConnection(host, port, null, null);
    }
    private void startConnection(String host, int port, String userId, String password) throws IOException {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("请输入服务端 IP 或主机名");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须为 1～65535");
        synchronized (lifecycle) {
            if (disposed) throw new IllegalStateException("客户端已关闭");
            if (state != State.DISCONNECTED) throw new IllegalStateException("请先断开当前连接");
            Connection connection = new Connection(userId);
            connection.history = new HistoryChannel(message -> submitControl(connection, message), event -> emit(connection, event), listener,
                    deadlines, connection::nextId, () -> connection.userId, loginTimeoutMillis);
            connection.heartbeat = new Heartbeat(heartbeatSettings, message -> submitHeartbeat(connection, message),
                    () -> terminate(connection, "心跳超时，连接已中断，请手动重新连接；待发送消息未确认"));
            active = connection;
            state = State.CONNECTING;
            emit(connection, () -> listener.onStateChanged(State.CONNECTING, "正在连接服务器…"));
            connection.deadline = deadlines.schedule(() -> terminate(connection, "连接或登录超时，请检查地址后重新连接"),
                    (long) connectTimeoutMillis + loginTimeoutMillis, TimeUnit.MILLISECONDS);
            connection.sender.execute(() -> establish(connection, host.strip(), port, password));
        }
    }

    private void establish(Connection connection, String host, int port, String password) {
        try {
            connection.socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            connection.socket.setTcpNoDelay(true);
            connection.socket.setSoTimeout(loginTimeoutMillis);
            String id = connection.nextId();
            connection.authRequest = id;
            MessageCodec.write(connection.socket.getOutputStream(), Message.of(connection.gateway ? MessageType.HELLO : MessageType.LOGIN)
                    .requestId(id).userId(connection.userId).password(password).build());
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
                if (message.type() == MessageType.PING || message.type() == MessageType.PONG) {
                    connection.heartbeat.receive(message); continue;
                }
                if (!connection.loggedIn) {
                    if (connection.gateway) {
                        handleAuthentication(connection, message); continue;
                    }
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
                        connection.heartbeat.start();
                        emit(connection, () -> listener.onProfile(UserProfile.from(message)));
                        emit(connection, () -> listener.onStateChanged(State.CONNECTED, "已连接 · " + connection.userId));
                    }
                } else {
                    dispatch(connection, message);
                }
            }
        } catch (IOException | RuntimeException ex) { reason = "连接中断：" + ex.getMessage() + "，请手动重新连接"; }
        finally { terminate(connection, reason); }
    }

    private void handleAuthentication(Connection c, Message message) throws IOException {
        synchronized (lifecycle) {
            if (active != c || c.closed.get()) return;
            if (!java.util.Objects.equals(c.authRequest, message.requestId())) throw new IOException("认证响应编号不匹配");
            if (message.type() == MessageType.HELLO_OK && state == State.CONNECTING) {
                c.deadline.cancel(false); c.socket.setSoTimeout(0); c.authRequest = null;
                state = State.SERVER_CONNECTED; c.heartbeat.start();
                emit(c, () -> listener.onStateChanged(State.SERVER_CONNECTED, "服务器已连接，请登录或注册"));
            } else if (message.type() == MessageType.REGISTER_OK && c.registrationPassword != null) {
                String password = c.registrationPassword; c.registrationPassword = null;
                c.userId = Validation.userId(message.userId());
                emit(c, () -> listener.onRegistered(c.userId));
                sendAuthentication(c, Message.of(MessageType.LOGIN).userId(c.userId).password(password));
            } else if (message.type() == MessageType.LOGIN_OK && c.userId != null && c.userId.equals(message.userId()) && state == State.AUTHENTICATING) {
                c.deadline.cancel(false); c.authRequest = null; c.loggedIn = true;
                state = State.CONNECTED;
                emit(c, () -> listener.onProfile(UserProfile.from(message)));
                emit(c, () -> listener.onStateChanged(State.CONNECTED, "已登录 · " + c.userId));
            } else if (message.type() == MessageType.ERROR && state == State.AUTHENTICATING) {
                c.deadline.cancel(false); c.authRequest = null; c.registrationPassword = null;
                state = State.SERVER_CONNECTED;
                emit(c, () -> listener.onStateChanged(State.SERVER_CONNECTED, "服务器已连接，认证未成功"));
                emit(c, () -> listener.onError(message));
            } else throw new IOException("服务端返回无效认证响应");
        }
    }

    public void login(String userId, String password) throws IOException {
        String id = Validation.userId(userId);
        validatePassword(password);
        synchronized (lifecycle) {
            Connection c = serverConnection(); c.userId = id;
            sendAuthentication(c, Message.of(MessageType.LOGIN).userId(id).password(password));
        }
    }
    public void registerAndLogin(String userId, String nickname, String password, boolean random) throws IOException {
        if (!random) userId = Validation.userId(userId);
        if (nickname == null || nickname.isBlank() || nickname.strip().length() > 40)
            throw new ProtocolException("INVALID_NICKNAME", "昵称须为 1～40 字符");
        validatePassword(password);
        synchronized (lifecycle) {
            Connection c = serverConnection(); c.registrationPassword = password;
            try { sendAuthentication(c, Message.of(MessageType.REGISTER).userId(random ? null : userId)
                    .nickname(nickname).password(password).usernameMode(random ? "RANDOM" : "MANUAL")); }
            catch (IOException ex) { c.registrationPassword = null; throw ex; }
        }
    }
    private Connection serverConnection() throws IOException {
        if (state != State.SERVER_CONNECTED || active == null || active.closed.get()) throw new IOException("请先连接服务器，或等待当前操作完成");
        return active;
    }
    private static void validatePassword(String password) throws ProtocolException {
        if (password == null || password.length() < 6 || password.length() > 128)
            throw new ProtocolException("INVALID_PASSWORD", "密码长度须为 6～128 字符");
    }
    private void sendAuthentication(Connection c, Message.Builder builder) throws IOException {
        c.authRequest = c.nextId();
        Message request = builder.requestId(c.authRequest).build(); MessageCodec.encode(request);
        state = State.AUTHENTICATING;
        if (c.deadline != null) c.deadline.cancel(false);
        c.deadline = deadlines.schedule(() -> terminate(c, "认证响应超时，请重新连接服务器"), loginTimeoutMillis, TimeUnit.MILLISECONDS);
        try { c.sender.execute(() -> {
            try { if (!c.closed.get()) MessageCodec.write(c.socket.getOutputStream(), request); }
            catch (IOException ex) { terminate(c, "认证发送失败，请重新连接服务器"); }
        }); } catch (RejectedExecutionException ex) { terminate(c, "认证请求无法入队"); throw new IOException("认证请求无法入队", ex); }
        emit(c, () -> listener.onStateChanged(State.AUTHENTICATING, "正在验证账号…"));
    }

    private void dispatch(Connection connection, Message message) throws IOException {
        if (connection.history.receive(message)) return;
        synchronized (connection) {
        if (message.requestId() != null && message.requestId().equals(connection.exportRequest)) {
            if (connection.exportDeadline != null) connection.exportDeadline.cancel(false);
            try {
                if (message.type() == MessageType.ERROR) { closeExport(connection); emit(connection, () -> listener.onError(message)); return; }
                if (message.type() == MessageType.EXPORT_END) {
                    java.nio.file.Path path = connection.export.finish(); connection.export = null; connection.exportRequest = null; connection.exporting.set(false);
                    emit(connection, () -> listener.onExported(path)); return;
                }
                if (message.type() != MessageType.EXPORT_PAGE || message.record() == null || message.cursor() == null)
                    throw new IOException("无效导出响应");
                connection.export.append(message.record()); requestExport(connection, message.cursor()); return;
            } catch (IOException | RuntimeException ex) { exportFailed(connection); return; }
        }
        }
        synchronized (lifecycle) {
            if (message.requestId() != null && message.requestId().equals(connection.profileRequest)) {
                connection.profileDeadline.cancel(false); connection.profileRequest = null;
                if (message.type() == MessageType.ERROR) { emit(connection, () -> listener.onError(message)); return; }
                if (message.type() != MessageType.PROFILE_OK || !connection.userId.equals(message.userId()) || message.nickname() == null)
                    throw new IOException("无效个人资料响应");
                AvatarData.decode(message.avatar());
                emit(connection, () -> listener.onProfile(UserProfile.from(message))); return;
            }
        }
        switch (message.type()) {
            case PING, PONG -> connection.heartbeat.receive(message);
            case EXPORT_PAGE, EXPORT_END -> { /* Ignore a response arriving after a cancelled export. */ }
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
            case DISPLAY_PROFILE -> {
                String user = Validation.userId(message.userId());
                String name = message.nickname();
                if (!user.equals(message.userId()) || name == null || name.isBlank() || name.length() > 40
                        || message.avatar() == null || message.revision() == null || message.revision() < 0) {
                    throw new IOException("无效用户展示资料");
                }
                var profile = new ContactProfile(user, name, AvatarData.decode(message.avatar()), message.revision());
                emit(connection, () -> listener.onDisplayProfile(profile));
            }
            case SYSTEM -> emit(connection, () -> listener.onSystem(message));
            case ERROR -> emit(connection, () -> listener.onError(message));
            case PROFILE_OK -> { /* Ignore expired profile responses. */ }
            default -> throw new IOException("服务端发送了不允许的消息类型");
        }
    }

    public void sendGroup(String content) throws IOException { submitChat(null, content); }
    private Connection authenticated() throws IOException {
        synchronized (lifecycle) {
            if (state != State.CONNECTED || active == null || active.closed.get()) throw new IOException("请先登录");
            return active;
        }
    }
    public void loadHistory() throws IOException { authenticated().history.load(); }
    public void deleteHistory(String scope, String id) throws IOException {
        // The server invalidates affected export cursors; their background response/timeout path cleans files.
        Connection c = authenticated(); c.history.delete(scope, id);
    }
    private void submitControl(Connection c, Message message) throws IOException {
        if (c.closed.get()) throw new IOException("连接已关闭"); MessageCodec.encode(message);
        try { c.sender.execute(() -> {
            try { if (!c.closed.get()) MessageCodec.write(c.socket.getOutputStream(), message); }
            catch (IOException ex) { terminate(c, "历史请求发送失败，请重新连接"); }
        }); } catch (RejectedExecutionException ex) { throw new IOException("请求无法入队", ex); }
    }

    public void updateProfile(String nickname, String avatar) throws IOException {
        profileRequest(Message.of(MessageType.PROFILE_UPDATE).nickname(nickname).avatar(avatar));
    }
    public void refreshProfile() throws IOException { profileRequest(Message.of(MessageType.PROFILE_GET)); }
    private void profileRequest(Message.Builder builder) throws IOException {
        synchronized (lifecycle) {
            Connection c = active;
            if (state != State.CONNECTED || c == null || c.closed.get()) throw new IOException("请先登录");
            if (c.profileRequest != null) throw new IOException("请等待当前资料操作完成");
            String id = "profile-" + c.nextId();
            Message request = builder.requestId(id).build();
            MessageCodec.encode(request);
            c.profileRequest = id;
            c.profileDeadline = deadlines.schedule(() -> {
                synchronized (lifecycle) {
                    if (!id.equals(c.profileRequest) || c.closed.get()) return;
                    c.profileRequest = null;
                    emit(c, () -> listener.onError(Message.error(id, "PROFILE_FAILED", "资料响应超时，保存结果未确认，请重新登录核对")));
                }
            }, loginTimeoutMillis, TimeUnit.MILLISECONDS);
            try { c.sender.execute(() -> {
                try { if (!c.closed.get()) MessageCodec.write(c.socket.getOutputStream(), request); }
                catch (IOException ex) { terminate(c, "资料保存失败，连接已断开"); }
            }); } catch (RejectedExecutionException ex) {
                c.profileDeadline.cancel(false); c.profileRequest = null; throw new IOException("发送队列已满，请稍后重试", ex);
            }
        }
    }

    public void register(String host, int port, String userId, String nickname, String password, boolean random) {
        if (state != State.DISCONNECTED) throw new IllegalStateException("请先断开当前连接");
        daemonFactory("ltalk-client-register-").newThread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis); socket.setSoTimeout(loginTimeoutMillis);
                MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.REGISTER).requestId("register")
                        .userId(random ? null : userId).nickname(nickname).password(password).usernameMode(random ? "RANDOM" : "MANUAL").build());
                Message result = new MessageCodec.FrameReader(socket.getInputStream()).read();
                if (result == null) throw new IOException("注册连接已关闭");
                callbacks.execute(() -> { if (result.type() == MessageType.REGISTER_OK) listener.onRegistered(result.userId()); else listener.onError(result); });
            } catch (IOException | RuntimeException ex) { callbacks.execute(() -> listener.onError(Message.error(null, "REGISTER_FAILED", "注册失败：" + ex.getMessage()))); }
        }).start();
    }

    public void exportHistory(java.nio.file.Path directory, String format) throws IOException {
        exportHistory(directory, format, null);
    }
    public void exportHistory(java.nio.file.Path directory, String format, String scope) throws IOException {
        export(directory, format, scope, false, false);
    }
    public void exportTo(java.nio.file.Path path, String format, String scope, boolean replace) throws IOException {
        export(path, format, scope, true, replace);
    }
    private void export(java.nio.file.Path directory, String format, String scope, boolean explicit, boolean replace) throws IOException {
        synchronized (lifecycle) {
            Connection c = active;
            if (state != State.CONNECTED || c == null || c.closed.get()) throw new IOException("请先登录");
            ConversationScope.validate(c.userId, scope);
            if (!c.exporting.compareAndSet(false, true)) throw new IOException("已有导出任务");
            c.exportScope = scope;
            try { c.sender.execute(() -> {
                try { HistoryExport job = explicit ? new HistoryExport(directory, format, replace) : new HistoryExport(directory, c.userId, format);
                    synchronized (c) {
                        if (c.closed.get()) { job.close(); return; }
                        c.export = job;
                    }
                    requestExport(c, null); }
                catch (IOException | RuntimeException ex) { exportFailed(c); }
            }); } catch (RejectedExecutionException ex) { c.exporting.set(false); throw new IOException("发送队列已满"); }
        }
    }
    private void requestExport(Connection c, String cursor) throws IOException {
        synchronized (c) {
        if (c.closed.get() || c.export == null) throw new IOException("导出已取消");
        c.exportRequest = c.nextId();
        String expected = c.exportRequest;
        c.exportDeadline = deadlines.schedule(() -> {
            synchronized (c) { if (expected.equals(c.exportRequest)) exportFailed(c); }
        }, loginTimeoutMillis, TimeUnit.MILLISECONDS);
        Message request = Message.of(MessageType.EXPORT_REQUEST).requestId(c.exportRequest).cursor(cursor).conversationId(c.exportScope).build();
        try { c.sender.execute(() -> {
            try { if (!c.closed.get()) MessageCodec.write(c.socket.getOutputStream(), request); }
            catch (IOException ex) { exportFailed(c); }
        }); } catch (RejectedExecutionException ex) { throw new IOException("导出请求无法入队", ex); }
        }
    }
    private void exportFailed(Connection c) {
        closeExport(c); emit(c, () -> listener.onError(Message.error(null, "EXPORT_FAILED", "导出失败，请检查连接、目录权限及磁盘空间")));
    }
    private void closeExport(Connection c) {
        synchronized (c) {
        if (c.exportDeadline != null) c.exportDeadline.cancel(false);
        HistoryExport job = c.export; c.export = null; c.exportRequest = null; c.exporting.set(false);
        if (job != null) try { job.close(); } catch (IOException ignored) { }
        }
    }

    private void submitHeartbeat(Connection connection, Message message) {
        if (connection.closed.get()) return;
        try {
            connection.sender.execute(() -> {
                if (connection.closed.get()) return;
                try { MessageCodec.write(connection.socket.getOutputStream(), message); }
                catch (IOException ex) { terminate(connection, "心跳写入失败，请手动重新连接；待发送消息未确认"); }
            });
        } catch (RejectedExecutionException ex) {
            terminate(connection, "心跳无法入队，请手动重新连接；待发送消息未确认");
        }
    }
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
        connection.heartbeat.close();
        connection.history.close();
        connection.registrationPassword = null;
        if (connection.profileDeadline != null) connection.profileDeadline.cancel(false);
        connection.profileRequest = null;
        closeExport(connection);
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
        volatile String userId;
        final boolean gateway;
        volatile String authRequest, registrationPassword;
        volatile String profileRequest;
        volatile ScheduledFuture<?> profileDeadline;
        final Socket socket = new Socket();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicLong sequence = new AtomicLong();
        final ExecutorService sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), daemonFactory("ltalk-client-send-"), new ThreadPoolExecutor.AbortPolicy());
        volatile ScheduledFuture<?> deadline;
        Heartbeat heartbeat;
        HistoryChannel history;
        final AtomicBoolean exporting = new AtomicBoolean();
        volatile HistoryExport export;
        volatile String exportRequest;
        volatile String exportScope;
        volatile ScheduledFuture<?> exportDeadline;
        boolean loggedIn;
        long lastRevision = -1;
        Connection(String userId) { this.userId = userId; gateway = userId == null; }
        String nextId() { return "r" + sequence.incrementAndGet(); }
    }
}
