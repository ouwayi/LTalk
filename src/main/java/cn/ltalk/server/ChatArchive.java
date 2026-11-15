package cn.ltalk.server;

import cn.ltalk.common.*;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Per-account journals with bounded snapshots and owner-scoped atomic deletion; no client file paths. */
final class ChatArchive {
    private final Path directory;
    private final Gson gson = new Gson();
    private Instant last = Instant.MIN;
    private final Map<String, Set<Cursor>> cursors = new HashMap<>();
    ChatArchive(Path directory) throws IOException {
        this.directory = directory; Files.createDirectories(directory);
        Path clock = directory.resolve("last-time");
        if (Files.exists(clock)) {
            try { last = Instant.parse(Files.readString(clock)); }
            catch (RuntimeException ex) { throw new IOException("历史时间元数据损坏", ex); }
        }
    }
    synchronized Message record(MessageType type, String sender, String nickname, Message request,
                                Collection<String> recipients) throws IOException {
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
        return Message.of(type).requestId(request.requestId()).from(sender).nickname(nickname).to(record.recipient())
                .content(record.content()).serverTime(record.sentAt()).messageId(id).conversationId(conversation).build();
    }
    synchronized Cursor open(String user) throws IOException {
        return open(user, null);
    }
    synchronized Cursor open(String user, String scope) throws IOException {
        ConversationScope.validate(user, scope);
        Path path = directory.resolve(AccountStore.fileKey(user) + ".jsonl");
        Cursor cursor = new Cursor(Files.exists(path) ? new RandomAccessFile(path.toFile(), "r") : null, scope);
        cursors.computeIfAbsent(user, key -> new HashSet<>()).add(cursor); return cursor;
    }
    synchronized void delete(String user, String scope, String id) throws IOException {
        if (scope == null) throw new ProtocolException("BAD_MESSAGE", "删除必须指定会话");
        ConversationScope.validate(user, scope);
        if (id != null) {
            try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException ex) { throw new ProtocolException("BAD_MESSAGE", "无效消息编号"); }
        }
        Set<Cursor> active = cursors.remove(user);
        if (active != null) for (Cursor cursor : List.copyOf(active)) cursor.close();
        Path original = directory.resolve(AccountStore.fileKey(user) + ".jsonl");
        Path temporary = Files.createTempFile(directory, ".history-delete-", ".tmp"); boolean found = false;
        try {
            try (Cursor input = new Cursor(Files.exists(original) ? new RandomAccessFile(original.toFile(), "r") : null, null);
                 BufferedWriter output = Files.newBufferedWriter(temporary)) {
                ChatRecord record;
                while ((record = input.next()) != null) {
                    if (scope.equals(record.conversationId()) && (id == null || id.equals(record.messageId()))) found = true;
                    else { output.write(gson.toJson(record)); output.newLine(); }
                }
            }
            if (id != null && !found) throw new ProtocolException("NOT_FOUND", "本人此会话中没有这条记录");
            Files.move(temporary, original, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    final class Cursor implements AutoCloseable {
        final String token = UUID.randomUUID().toString();
        final RandomAccessFile file;
        final long end;
        final String scope;
        boolean closed;
        Cursor(RandomAccessFile file, String scope) throws IOException {
            this.file = file; this.scope = scope; end = file == null ? 0 : file.length();
        }
        ChatRecord next() throws IOException {
            synchronized (ChatArchive.this) {
            if (closed) throw new IOException("历史快照已失效，请重新加载或导出");
            ChatRecord record;
            do { record = readNext(); } while (record != null && scope != null && !scope.equals(record.conversationId()));
            return record;
            }
        }
        private ChatRecord readNext() throws IOException {
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
        @Override public void close() throws IOException {
            synchronized (ChatArchive.this) {
                if (closed) return; closed = true;
                for (Set<Cursor> set : cursors.values()) set.remove(this);
                cursors.entrySet().removeIf(entry -> entry.getValue().isEmpty());
                if (file != null) file.close();
            }
        }
    }
}
