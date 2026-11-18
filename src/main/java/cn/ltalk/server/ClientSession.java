package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

final class ClientSession implements Runnable {
    private static final Logger LOG = Logger.getLogger(ClientSession.class.getName());
    private final ChatServer server;
    private final Socket socket;
    private final SessionOutput output = new SessionOutput();
    private final Map<String, Long> sentProfiles = new HashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean evicted = new AtomicBoolean();
    boolean unauthenticated = true; // Guarded by ChatServer's admission monitor.
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
        heartbeat = new Heartbeat(settings, message -> server.deliver(this, message), () -> close("心跳超时"));
    }

    void awaitTermination() throws InterruptedException {
        heartbeat.awaitTermination(2, TimeUnit.SECONDS);
        output.awaitTermination();
    }
    boolean isClosed() { return closed.get() || evicted.get(); }
    InetAddress remoteAddress() { return socket.getInetAddress(); }

    boolean send(Message message, byte[] frame, CompletableFuture<Void> written) {
        if (isClosed()) return false;
        if (message.type() != MessageType.DISPLAY_PROFILE) return output.offer(frame, written);
        synchronized (sentProfiles) {
            if (message.revision() <= sentProfiles.getOrDefault(message.userId(), -1L)) {
                if (written != null) written.complete(null);
                return true;
            }
            if (!output.offer(frame, written)) return false;
            sentProfiles.put(message.userId(), message.revision());
            return true;
        }
    }

    boolean evict() { return !closed.get() && evicted.compareAndSet(false, true); }

    void loginTimedOut() {
        // Absolute deadline, including clients that keep trickling bytes without finishing LOGIN.
        if (!ready && !greeted) close("登录超时");
    }

    @Override public void run() {
        String reason = "客户端关闭连接";
        try {
            Socket transport = server.transport(socket);
            var input = new BufferedInputStream(transport.getInputStream());
            MessageCodec.FrameReader reader = new MessageCodec.FrameReader(input);
            output.start(transport.getOutputStream(), ex -> close("写入失败：" + ex.getMessage()));
            boolean first = true;
            while (!isClosed()) {
                Message request;
                try {
                    request = reader.read();
                } catch (ProtocolException ex) {
                    first = false;
                    LOG.info(() -> "协议错误 user=" + userId + " code=" + ex.code());
                    Message error = Message.error(ex.requestId(), ex.code(), ex.getMessage());
                    if (ex.code().equals("FRAME_TOO_LARGE")) { server.deliverAndWait(this, error); reason = ex.code(); break; }
                    server.deliver(this, error);
                    continue;
                }
                if (request == null) break;
                if (first && request.type() == MessageType.ATTACH_OPEN) {
                    server.attachments().transfer(this, transport, input, reader, request);
                    break;
                }
                first = false;
                LOG.fine(() -> "request user=" + userId + " type=" + request.type());
                server.router().handle(this, request);
            }
        } catch (IOException ex) {
            reason = "连接读写中断：" + ex.getMessage();
        } finally {
            close(reason);
        }
    }

    void beginTransfer() throws IOException {
        ScheduledFuture<?> initial = loginDeadline;
        loginDeadline = server.transferDeadline(this);
        if (initial != null) initial.cancel(false);
        if (isClosed()) {
            loginDeadline.cancel(false);
            throw new IOException("传输连接已关闭");
        }
        server.authenticated(this);
    }

    void close(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        ScheduledFuture<?> deadline = loginDeadline;
        if (deadline != null) deadline.cancel(false);
        // Closing the socket does not wait for a writer, and unblocks pending read/write calls.
        output.close();
        try { socket.close(); } catch (IOException ignored) { }
        heartbeat.close();
        closeExport();
        closeHistory();
        server.sessionClosed(this, reason);
    }

    @Override public String toString() { return userId == null ? socket.getRemoteSocketAddress().toString() : userId; }
    synchronized void closeExport() {
        if (exportCursor != null) exportCursor.close();
        exportCursor = null;
    }
    synchronized void closeHistory() {
        if (historyCursor != null) historyCursor.close();
        historyCursor = null;
    }
}
