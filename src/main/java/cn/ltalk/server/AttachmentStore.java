package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Authenticated, one-use transfer tickets and byte storage under the server data lock. */
final class AttachmentStore {
    private static final Logger LOG = Logger.getLogger(AttachmentStore.class.getName());
    private static final int MAX_ACTIVE = 8;
    private static final long TICKET_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final long DEFAULT_QUOTA = 1024L * 1024 * 1024;
    private final ChatServer server;
    private final Path directory;
    private final long quota = Long.getLong("ltalk.attachments.quotaBytes", DEFAULT_QUOTA);
    private final Map<ClientSession, Ticket> byOwner = new HashMap<>();
    private final Map<String, Ticket> unclaimed = new HashMap<>();
    private long reserved;
    private int active;

    private static final class Ticket {
        final String token = UUID.randomUUID().toString();
        final long expires = System.nanoTime() + TICKET_NANOS;
        final ClientSession owner;
        final String id, scope, requestId;
        final Message upload;
        ClientSession transfer;

        Ticket(ClientSession owner, Message request, Message upload) {
            this.owner = owner;
            this.upload = upload;
            id = upload == null ? request.messageId() : UUID.randomUUID().toString();
            scope = request.conversationId();
            requestId = request.requestId();
        }
    }

    AttachmentStore(ChatServer server, Path directory) throws IOException {
        this.server = server;
        this.directory = Files.createDirectories(directory);
        if (quota <= 0) throw new IOException("附件配额须为正整数");
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(".part")) Files.deleteIfExists(file);
                else if (name.endsWith(".blob")) reserved = Math.addExact(reserved, usage(Files.size(file)));
            }
        }
    }

    void prepare(ClientSession owner, Message request) throws IOException {
        Validation.requestId(request.requestId());
        Message upload = null;
        if (request.type() == MessageType.ATTACH_UPLOAD) {
            if (request.attachment() == null) throw new ProtocolException("BAD_ATTACHMENT", "缺少文件信息");
            if (request.to() != null && request.conversationId() != null)
                throw new ProtocolException("BAD_MESSAGE", "上传须指定单个会话");
            request.attachment().validateUpload();
            upload = Message.of(request.to() == null ? MessageType.GROUP_SEND : MessageType.PRIVATE_SEND)
                    .conversationId(request.to() == null ? request.conversationId() : null)
                    .to(request.to()).attachment(request.attachment().withoutOriginalImageName()).build();
            server.router().checkAttachmentUpload(owner, upload);
        } else {
            if (request.conversationId() == null) throw new ProtocolException("BAD_MESSAGE", "下载附件需要会话标识");
            ConversationScope.validate(owner.userId, request.conversationId());
            ConversationScope.uuid(request.messageId());
        }
        Ticket ticket = new Ticket(owner, request, upload);
        synchronized (this) {
            Ticket prior = byOwner.get(owner);
            if (prior != null) {
                if (prior.transfer != null || !expired(prior))
                    throw new ProtocolException("ATTACHMENT_BUSY", "当前账号已有文件传输");
                remove(prior);
            }
            if (owner.isClosed()) throw new ProtocolException("NOT_AUTHENTICATED", "请重新登录");
            byOwner.put(owner, ticket);
            unclaimed.put(ticket.token, ticket);
        }
        server.deliver(owner, Message.of(MessageType.ATTACH_TICKET).requestId(ticket.requestId).cursor(ticket.token).build());
    }

    private static boolean expired(Ticket ticket) { return System.nanoTime() - ticket.expires >= 0; }

    private synchronized Ticket claim(ClientSession transfer, String token) throws ProtocolException {
        Ticket ticket = unclaimed.remove(token);
        if (ticket == null) throw new ProtocolException("FORBIDDEN", "传输票据无效或已使用");
        if (expired(ticket) || !ticket.owner.ready || ticket.owner.isClosed()) {
            remove(ticket);
            throw new ProtocolException("FORBIDDEN", "传输票据已过期");
        }
        if (active >= MAX_ACTIVE) {
            remove(ticket);
            throw new ProtocolException("ATTACHMENT_BUSY", "服务器正在传输其他文件，请稍后重试");
        }
        active++;
        ticket.transfer = transfer;
        return ticket;
    }

    void cancel(ClientSession owner, String requestId) {
        ClientSession transfer;
        synchronized (this) {
            Ticket ticket = byOwner.get(owner);
            if (ticket == null || (requestId != null && !ticket.requestId.equals(requestId))) return;
            transfer = ticket.transfer;
            remove(ticket);
        }
        if (transfer != null) transfer.close("文件传输已取消");
    }

    private void remove(Ticket ticket) {
        byOwner.remove(ticket.owner, ticket);
        unclaimed.remove(ticket.token, ticket);
    }

    void transfer(ClientSession session, Socket socket, InputStream input, MessageCodec.FrameReader reader,
                  Message open) throws IOException {
        OutputStream output = socket.getOutputStream();
        Ticket ticket = null;
        Message reply;
        try {
            ConversationScope.uuid(open.cursor());
            ticket = claim(session, open.cursor());
            session.beginTransfer();
            socket.setSoTimeout(Attachment.IDLE_MILLIS);
            reply = ticket.upload == null ? download(ticket, output) : upload(ticket, input, reader, output);
        } catch (ProtocolException ex) {
            reply = Message.error(null, ex.code(), ex.getMessage());
        } finally {
            if (ticket != null) synchronized (this) { remove(ticket); active--; }
        }
        MessageCodec.write(output, reply);
    }

    private Message upload(Ticket ticket, InputStream input, MessageCodec.FrameReader reader, OutputStream output) throws IOException {
        Attachment metadata = ticket.upload.attachment();
        long cost = usage(metadata.size());
        synchronized (this) {
            if (cost > quota - reserved) throw new ProtocolException("ATTACHMENT_QUOTA", "服务器附件空间不足");
            reserved += cost;
        }
        Path part = directory.resolve(ticket.id + ".part");
        Path destination = blob(ticket.id);
        try {
            try (var file = Files.newOutputStream(part, StandardOpenOption.CREATE_NEW)) {
                MessageCodec.write(output, Message.of(MessageType.ATTACH_READY).attachment(metadata).build());
                Attachment.copy(input, file, metadata.size(), ignored -> { });
            }
            Message end = reader.read();
            if (end == null || end.type() != MessageType.ATTACH_COMPLETE)
                throw new ProtocolException("BAD_ATTACHMENT", "缺少完整上传确认");
            String thumbnail = metadata.kind() == Attachment.Kind.IMAGE ? ChatImage.thumbnail(part) : null;
            Attachment attachment = new Attachment(metadata.kind(), metadata.name(), metadata.size(), thumbnail);
            Message request = Message.of(ticket.upload.type()).to(ticket.upload.to()).conversationId(ticket.upload.conversationId())
                    .content(attachment.messageContent()).messageId(ticket.id).attachment(attachment).build();
            server.router().publishAttachment(ticket.owner, ticket.transfer, request,
                    () -> Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE));
            return Message.of(MessageType.ATTACH_COMPLETE).messageId(ticket.id).build();
        } finally {
            try {
                Files.deleteIfExists(part);
                if (!Files.exists(destination)) synchronized (this) { reserved -= cost; }
            } catch (IOException ex) { LOG.warning("无法清理未完成的附件：" + ticket.id); }
        }
    }

    private Message download(Ticket ticket, OutputStream output) throws IOException {
        ChatRecord row = server.archive().visibleRecord(ticket.owner.userId, ticket.scope, ticket.id);
        if (row == null || row.attachment() == null)
            throw new ProtocolException("ATTACHMENT_NOT_FOUND", "附件在此会话中不可查看");
        Path path = blob(ticket.id);
        if (!Files.isRegularFile(path) || Files.size(path) != row.attachment().size())
            throw new ProtocolException("ATTACHMENT_NOT_FOUND", "服务器上的附件已不可用");
        try (var file = Files.newInputStream(path)) {
            Attachment attachment = row.attachment();
            Attachment metadata = new Attachment(attachment.kind(), attachment.name(), attachment.size(), null);
            MessageCodec.write(output, Message.of(MessageType.ATTACH_READY).attachment(metadata).build());
            Attachment.copy(file, output, attachment.size(), ignored -> { });
            return Message.of(MessageType.ATTACH_COMPLETE).build();
        }
    }

    private Path blob(String id) { return directory.resolve(id + ".blob"); }
    private static long usage(long bytes) { return Math.max(4096, bytes); }
}
