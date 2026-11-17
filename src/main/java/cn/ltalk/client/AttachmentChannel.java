package cn.ltalk.client;

import cn.ltalk.common.*;

import javax.net.ssl.SSLContext;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** One cancellable file operation owned by a single authenticated chat connection. */
final class AttachmentChannel {
    private static final String PREFIX = "attachment-";
    @FunctionalInterface interface Sender { void send(Message message) throws IOException; }
    @FunctionalInterface interface Handle { void cancel(); }
    record Result(Path saved, BufferedImage image, String detail, boolean error) { }
    record Request(String scope, String target, String messageId, Attachment attachment, Path path,
                   Attachment.Kind upload, boolean replace) {
        static Request upload(String scope, String target, Path path, Attachment.Kind kind) {
            return new Request(scope, target, null, null, path, kind, false);
        }
        static Request download(String scope, String messageId, Attachment attachment, Path path, boolean replace) {
            return new Request(scope, null, messageId, attachment, path, null, replace);
        }
    }

    private final Sender sender;
    private final Consumer<Runnable> callbacks;
    private final Supplier<String> requestIds;
    private final ScheduledExecutorService deadlines;
    private final String host;
    private final int port, connectMillis;
    private final boolean tls;
    private final SSLContext tlsContext;
    private Job current;
    private boolean closed;

    AttachmentChannel(Sender sender, Consumer<Runnable> callbacks, Supplier<String> requestIds,
                      ScheduledExecutorService deadlines, String host, int port, int connectMillis,
                      boolean tls, SSLContext tlsContext) {
        this.sender = sender;
        this.callbacks = callbacks;
        this.requestIds = requestIds;
        this.deadlines = deadlines;
        this.host = host;
        this.port = port;
        this.connectMillis = connectMillis;
        this.tls = tls;
        this.tlsContext = tlsContext;
    }

    synchronized Handle start(Request request, IntConsumer progress, Consumer<Result> done) throws IOException {
        if (closed) throw new IOException("连接已关闭");
        if (current != null) throw new IOException("请等待当前文件传输结束");
        Job job = new Job(PREFIX + requestIds.get(), progress, done);
        current = job;
        Thread worker = new Thread(() -> execute(job, request), "ltalk-client-attachment");
        worker.setDaemon(true);
        worker.start();
        return () -> job.stop("已取消传输");
    }

    synchronized boolean receive(Message response) {
        String id = response.requestId();
        if (id == null || !id.startsWith(PREFIX)) return false;
        if (current != null && current.id.equals(id)) current.ticket.complete(response);
        return true;
    }

    void close() {
        Job job;
        synchronized (this) { closed = true; job = current; }
        if (job != null) job.stop("连接已断开，文件传输结束");
    }

    private void execute(Job job, Request request) {
        Result result;
        ScheduledFuture<?> deadline = null;
        Attachment expected = request.attachment();
        Path path = request.path();
        Attachment.Kind upload = request.upload();
        try (Socket raw = job.raw) {
            deadline = deadlines.schedule(() -> job.stop("文件传输超时"), Attachment.TRANSFER_SECONDS + 5, TimeUnit.SECONDS);
            if (upload != null) {
                if (!Files.isRegularFile(path)) throw new IOException("请选择一个文件");
                expected = new Attachment(upload, Attachment.safeName(path.getFileName().toString()), Files.size(path), null);
                expected.validateUpload();
            }
            Message prepare = Message.of(upload != null ? MessageType.ATTACH_UPLOAD : MessageType.ATTACH_DOWNLOAD)
                    .requestId(job.id).conversationId(request.scope()).to(request.target()).messageId(request.messageId())
                    .attachment(upload != null ? expected : null).build();
            job.checkRunning();
            sender.send(prepare);
            job.requested = true;
            Message ticket = expect(job.ticket.get(8, TimeUnit.SECONDS), MessageType.ATTACH_TICKET);
            ConversationScope.uuid(ticket.cursor());
            job.checkRunning();
            raw.connect(new InetSocketAddress(host, port), connectMillis);
            raw.setSoTimeout(Attachment.IDLE_MILLIS);
            Socket transport = raw;
            if (tls) {
                var secured = TlsTransport.layer(raw, tlsContext == null ? TlsTransport.clientContext() : tlsContext, host, true);
                secured.setSoTimeout(Attachment.IDLE_MILLIS);
                secured.startHandshake();
                transport = secured;
            }
            var input = new BufferedInputStream(transport.getInputStream());
            var output = new BufferedOutputStream(transport.getOutputStream());
            var reader = new MessageCodec.FrameReader(input);
            MessageCodec.write(output, Message.of(MessageType.ATTACH_OPEN).cursor(ticket.cursor()).build());
            Attachment ready = expect(reader.read(), MessageType.ATTACH_READY).attachment();
            if (ready == null || expected == null || ready.size() != expected.size()
                    || ready.kind() != expected.kind() || !expected.name().equals(ready.name()))
                throw new IOException("服务端返回的文件信息不匹配");
            if (upload != null) {
                try (var file = Files.newInputStream(path)) {
                    Attachment.copy(file, output, ready.size(), count -> job.progress(count, ready.size()));
                    if (file.read() != -1) throw new IOException("文件大小已改变，请重新选择");
                }
                job.checkRunning();
                job.awaitingPublication = true;
                MessageCodec.write(output, Message.of(MessageType.ATTACH_COMPLETE).build());
                expect(reader.read(), MessageType.ATTACH_COMPLETE);
                result = new Result(null, null, "文件已发送", false);
            } else {
                result = download(job, path, request.replace(), ready, input, reader);
            }
        } catch (Exception ex) {
            if (job.requested) {
                try { sender.send(Message.of(MessageType.ATTACH_CANCEL).requestId(job.id).build()); }
                catch (IOException ignored) { /* Login teardown also cancels the server ticket. */ }
            }
            String reason = job.stopped;
            boolean cancelled = "已取消传输".equals(reason);
            if (reason == null) {
                reason = ex instanceof TimeoutException ? "服务端未及时响应文件传输"
                        : ex.getMessage() == null ? "传输失败，请稍后重试" : ex.getMessage();
            }
            boolean uncertain = job.awaitingPublication && !(ex instanceof ProtocolException);
            if (uncertain) reason += "，发送结果待确认，请核对聊天记录";
            result = new Result(null, null, reason, !cancelled || uncertain);
        } finally {
            if (deadline != null) deadline.cancel(false);
            synchronized (this) { current = null; }
        }
        Result completed = result;
        callbacks.accept(() -> job.done.accept(completed));
    }

    private Result download(Job job, Path path, boolean replace, Attachment attachment, InputStream input,
                            MessageCodec.FrameReader reader) throws IOException {
        Path destination = path == null ? null : path.toAbsolutePath();
        Path temporary = destination == null ? Files.createTempFile("ltalk-image-", ".tmp")
                : Files.createTempFile(destination.getParent(), ".ltalk-download-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                Attachment.copy(input, output, attachment.size(), count -> job.progress(count, attachment.size()));
            }
            expect(reader.read(), MessageType.ATTACH_COMPLETE);
            job.checkRunning();
            if (destination == null)
                return new Result(null, ChatImage.read(temporary, 900), "原图已加载", false);
            if (replace) Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temporary, destination);
            return new Result(destination, null, "已保存 " + destination.getFileName(), false);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Message expect(Message reply, MessageType type) throws IOException {
        if (reply != null && reply.type() == MessageType.ERROR) throw new ProtocolException(reply.code(), reply.message());
        if (reply == null || reply.type() != type) throw new IOException("文件传输提前结束或响应无效");
        return reply;
    }

    private final class Job {
        final String id;
        final Socket raw = new Socket();
        final CompletableFuture<Message> ticket = new CompletableFuture<>();
        final IntConsumer progress;
        final Consumer<Result> done;
        volatile String stopped;
        boolean requested, awaitingPublication;
        int shown = -1;

        Job(String id, IntConsumer progress, Consumer<Result> done) {
            this.id = id;
            this.progress = progress;
            this.done = done;
        }

        synchronized void stop(String reason) {
            if (stopped != null) return;
            stopped = reason;
            try { raw.close(); } catch (IOException ignored) { }
            ticket.completeExceptionally(new IOException(reason));
        }

        void checkRunning() throws IOException {
            if (stopped != null) throw new IOException(stopped);
        }

        void progress(long count, long size) {
            int percent = (int) (100 * count / size);
            if (percent == shown) return;
            shown = percent;
            callbacks.accept(() -> progress.accept(percent));
        }
    }
}
