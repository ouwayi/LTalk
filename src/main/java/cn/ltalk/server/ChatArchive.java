package cn.ltalk.server;

import cn.ltalk.common.*;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Append-only per-account journals; no client supplies a filesystem path. */
final class ChatArchive {
    private final Path directory;
    private final Gson gson = new Gson();
    private Instant last = Instant.MIN;
    ChatArchive(Path directory) throws IOException {
        this.directory = directory; Files.createDirectories(directory);
        Path clock = directory.resolve("last-time");
        if (Files.exists(clock)) {
            try { last = Instant.parse(Files.readString(clock)); }
            catch (RuntimeException ex) { throw new IOException("历史时间元数据损坏", ex); }
        }
    }
    synchronized Message record(MessageType type, String sender, Message request, Collection<String> recipients) throws IOException {
        Instant now = Instant.now(); if (now.isBefore(last)) now = last; last = now;
        Files.writeString(directory.resolve("last-time"), now.toString());
        String conversation = type == MessageType.GROUP_MESSAGE ? "GROUP:PUBLIC" :
                "PRIVATE:" + (sender.compareTo(request.to()) < 0 ? sender + ":" + request.to() : request.to() + ":" + sender);
        String id = UUID.randomUUID().toString();
        ChatRecord record = new ChatRecord(id, conversation, sender,
                type == MessageType.PRIVATE_MESSAGE ? request.to() : null, request.content(), type.name(), now.toString());
        byte[] line = (gson.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
        for (String user : new HashSet<>(recipients)) Files.write(directory.resolve(AccountStore.fileKey(user) + ".jsonl"), line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return Message.of(type).requestId(request.requestId()).from(sender).to(record.recipient())
                .content(record.content()).serverTime(record.sentAt()).messageId(id).conversationId(conversation).build();
    }
    synchronized Cursor open(String user) throws IOException {
        Path path = directory.resolve(AccountStore.fileKey(user) + ".jsonl");
        return new Cursor(Files.exists(path) ? new RandomAccessFile(path.toFile(), "r") : null);
    }
    static final class Cursor implements AutoCloseable {
        final String token = UUID.randomUUID().toString();
        final RandomAccessFile file;
        final long end;
        Cursor(RandomAccessFile file) throws IOException { this.file = file; end = file == null ? 0 : file.length(); }
        ChatRecord next() throws IOException {
            if (file == null || file.getFilePointer() >= end) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int b;
            while (file.getFilePointer() < end && (b = file.read()) != '\n') {
                if (b == -1 || bytes.size() >= MessageCodec.MAX_FRAME_BYTES) throw new IOException("历史文件损坏");
                bytes.write(b);
            }
            try {
                ChatRecord r = new Gson().fromJson(bytes.toString(StandardCharsets.UTF_8), ChatRecord.class);
                if (r == null || r.messageId() == null || r.content() == null || r.sentAt() == null) throw new IOException("历史文件损坏");
                return r;
            } catch (RuntimeException ex) { throw new IOException("历史文件损坏", ex); }
        }
        @Override public void close() throws IOException { if (file != null) file.close(); }
    }
}
