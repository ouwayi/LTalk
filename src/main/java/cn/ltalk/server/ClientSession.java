package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class ClientSession implements Runnable {
    private static final Logger LOG = Logger.getLogger(ClientSession.class.getName());
    private final ChatServer server;
    private final Socket socket;
    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    volatile String userId;
    volatile boolean ready;
    volatile ScheduledFuture<?> loginDeadline;

    ClientSession(ChatServer server, Socket socket) { this.server = server; this.socket = socket; }
    boolean isClosed() { return closed.get(); }

    void send(Message message) throws IOException {
        synchronized (writeLock) {
            if (closed.get()) throw new IOException("连接已关闭");
            MessageCodec.write(socket.getOutputStream(), message);
        }
    }

    void loginTimedOut() {
        // Absolute deadline, including clients that keep trickling bytes without finishing LOGIN.
        if (!ready) close("登录超时");
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
        server.sessionClosed(this, reason);
    }

    @Override public String toString() { return userId == null ? socket.getRemoteSocketAddress().toString() : userId; }
}
