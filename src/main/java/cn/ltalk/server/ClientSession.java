package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class ClientSession implements Runnable {
    private static final Logger LOG = Logger.getLogger(ClientSession.class.getName());
    private final ChatServer server;
    private final Socket socket;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService heartbeatWriter = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "ltalk-heartbeat-write");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    final Heartbeat heartbeat;
    volatile String userId;
    volatile boolean ready;
    volatile boolean greeted;
    volatile ScheduledFuture<?> loginDeadline;
    ChatArchive.Cursor exportCursor;
    ChatArchive.Cursor historyCursor;

    ClientSession(ChatServer server, Socket socket, HeartbeatSettings settings) {
        this.server = server;
        this.socket = socket;
        heartbeat = new Heartbeat(settings, this::submitHeartbeat, () -> close("心跳超时"));
    }

    private void submitHeartbeat(Message message) {
        if (closed.get()) return;
        try { heartbeatWriter.execute(() -> server.deliver(this, message)); }
        catch (RejectedExecutionException ex) { close("心跳发送队列已满或关闭"); }
    }

    void awaitHeartbeatTermination() throws InterruptedException {
        heartbeat.awaitTermination(2, TimeUnit.SECONDS);
        heartbeatWriter.awaitTermination(2, TimeUnit.SECONDS);
    }
    boolean isClosed() { return closed.get(); }

    void send(Message message) throws IOException {
        synchronized (writeLock) {
            if (closed.get()) throw new IOException("连接已关闭");
            MessageCodec.write(socket.getOutputStream(), message);
        }
    }

    void loginTimedOut() {
        // Absolute deadline, including clients that keep trickling bytes without finishing LOGIN.
        if (!ready && !greeted) close("登录超时");
    }

    @Override public void run() {
        String reason = "客户端关闭连接";
        try {
            MessageCodec.FrameReader reader = new MessageCodec.FrameReader(socket.getInputStream());
            while (!closed.get()) {
                Message request;
                try {
                    request = reader.read();
                } catch (ProtocolException ex) {
                    LOG.info(() -> "协议错误 user=" + userId + " code=" + ex.code());
                    server.deliver(this, Message.error(ex.requestId(), ex.code(), ex.getMessage()));
                    if (ex.code().equals("FRAME_TOO_LARGE")) { reason = ex.code(); break; }
                    continue;
                }
                if (request == null) break;
                LOG.fine(() -> "request user=" + userId + " type=" + request.type());
                server.router().handle(this, request);
            }
        } catch (IOException ex) {
            reason = "连接读写中断：" + ex.getMessage();
        } finally {
            close(reason);
        }
    }

    void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        ScheduledFuture<?> deadline = loginDeadline;
        if (deadline != null) deadline.cancel(false);
        // Closing the socket does not wait for a writer, and unblocks pending read/write calls.
        try { socket.close(); } catch (IOException ignored) { }
        heartbeat.close();
        heartbeatWriter.shutdownNow();
        closeExport();
        closeHistory();
        server.sessionClosed(this, reason);
    }

    @Override public String toString() { return userId == null ? socket.getRemoteSocketAddress().toString() : userId; }
    synchronized void closeExport() {
        if (exportCursor != null) try { exportCursor.close(); } catch (IOException ignored) { }
        exportCursor = null;
    }
    synchronized void closeHistory() {
        if (historyCursor != null) try { historyCursor.close(); } catch (IOException ignored) { }
        historyCursor = null;
    }
}
