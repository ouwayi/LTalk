package cn.ltalk.server;

import cn.ltalk.common.*;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Shared public journal and account journals, with bounded snapshots and owner-scoped deletion. */
final class ChatArchive {
    private final Path directory;
    private final Path publicJournal;
    private final Gson gson = new Gson();
    private Instant last = Instant.MIN;
    private final Map<String, Set<Cursor>> cursors = new HashMap<>();
    ChatArchive(Path directory) throws IOException {
        this.directory = directory; Files.createDirectories(directory);
        publicJournal = directory.resolve("public.jsonl");
        Path clock = directory.resolve("last-time");
        if (Files.exists(clock)) {
            try { last = Instant.parse(Files.readString(clock)); }
            catch (RuntimeException ex) { throw new IOException("历史时间元数据损坏", ex); }
        }
    }
    synchronized Message record(MessageType type, String sender, String nickname, Message request,
                                Collection<String> recipients) throws IOException {
        Instant now = Instant.now();
        if (!now.isAfter(last)) now = last.plusNanos(1);
        Files.writeString(directory.resolve("last-time"), now.toString());
        last = now;
        boolean publicMessage = type == MessageType.GROUP_MESSAGE;
        String conversation = publicMessage ? ConversationScope.PUBLIC : ConversationScope.privateChat(sender, request.to());
        String id = UUID.randomUUID().toString();
        ChatRecord record = new ChatRecord(id, conversation, sender,
                publicMessage ? null : request.to(), request.content(), type.name(), now.toString());
        byte[] line = (gson.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
        if (publicMessage) {
            // Keep the previous readable prefix if an append fails partway.
            try (RandomAccessFile file = new RandomAccessFile(publicJournal.toFile(), "rw")) {
                long end = file.length();
                file.seek(end);
                try { file.write(line); }
                catch (IOException ex) {
                    try { file.setLength(end); }
                    catch (IOException rollback) { ex.addSuppressed(rollback); }
                    throw ex;
                }
            }
        } else {
            for (String user : new HashSet<>(recipients)) Files.write(accountJournal(user), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return Message.of(type).requestId(request.requestId()).from(sender).nickname(nickname).to(record.recipient())
                .content(record.content()).serverTime(record.sentAt()).messageId(id).conversationId(conversation).build();
    }
    synchronized Cursor open(String user) throws IOException {
        return open(user, null);
    }
    synchronized Cursor open(String user, String scope) throws IOException {
        ConversationScope.validate(user, scope);
        Cursor cursor = snapshot(user, scope);
        cursors.computeIfAbsent(user, key -> new HashSet<>()).add(cursor); return cursor;
    }

    private Path accountJournal(String user) {
        return directory.resolve(AccountStore.fileKey(user) + ".jsonl");
    }

    private Cursor snapshot(String user, String scope) throws IOException {
        boolean includePublic = scope == null || ConversationScope.PUBLIC.equals(scope);
        PublicDeletion deletion = includePublic ? readPublicDeletion(user) : PublicDeletion.NONE;
        Journal personal = new Journal(accountJournal(user));
        try {
            Journal shared = includePublic ? new Journal(publicJournal) : null;
            return new Cursor(personal, shared, scope, deletion);
        } catch (IOException ex) {
            try { personal.close(); }
            catch (IOException close) { ex.addSuppressed(close); }
            throw ex;
        }
    }

    private record PublicDeletion(String clearedThrough, Set<String> hiddenIds) {
        static final PublicDeletion NONE = new PublicDeletion(null, Set.of());
    }

    private Path publicDeletionFile(String user) {
        return directory.resolve(AccountStore.fileKey(user) + ".public-deletions.json");
    }

    private PublicDeletion readPublicDeletion(String user) throws IOException {
        Path path = publicDeletionFile(user);
        if (!Files.exists(path)) return PublicDeletion.NONE;
        try {
            PublicDeletion deletion = gson.fromJson(Files.readString(path), PublicDeletion.class);
            if (deletion == null || deletion.hiddenIds() == null) throw new IllegalArgumentException();
            if (deletion.clearedThrough() != null) Instant.parse(deletion.clearedThrough());
            return deletion;
        } catch (RuntimeException ex) { throw new IOException("公共聊天室删除记录损坏", ex); }
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
        if (ConversationScope.PUBLIC.equals(scope)) {
            deletePublic(user, id);
            return;
        }
        Path original = accountJournal(user);
        Path temporary = Files.createTempFile(directory, ".history-delete-", ".tmp"); boolean found = false;
        try {
            try (Journal input = new Journal(original);
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

    private void deletePublic(String user, String id) throws IOException {
        PublicDeletion updated;
        try (Cursor input = snapshot(user, ConversationScope.PUBLIC)) {
            ChatRecord record;
            if (id == null) {
                while (input.next() != null) { /* Check the snapshot before publishing the clear. */ }
                updated = new PublicDeletion(last.toString(), Set.of());
            } else {
                do { record = input.next(); } while (record != null && !id.equals(record.messageId()));
                if (record == null) throw new ProtocolException("NOT_FOUND", "本人此会话中没有这条记录");
                Set<String> hiddenIds = new HashSet<>(input.deletion.hiddenIds());
                hiddenIds.add(id);
                updated = new PublicDeletion(input.deletion.clearedThrough(), hiddenIds);
            }
        }
        Path temporary = Files.createTempFile(directory, ".public-delete-", ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(updated));
            Files.move(temporary, publicDeletionFile(user), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    final class Cursor implements AutoCloseable {
        final String token = UUID.randomUUID().toString();
        final Journal personal, shared;
        final String scope;
        final PublicDeletion deletion;
        final Instant clearedThrough;
        ChatRecord personalHead, sharedHead;
        boolean closed;

        Cursor(Journal personal, Journal shared, String scope, PublicDeletion deletion) {
            this.personal = personal; this.shared = shared; this.scope = scope; this.deletion = deletion;
            clearedThrough = deletion.clearedThrough() == null ? Instant.MIN : Instant.parse(deletion.clearedThrough());
        }

        ChatRecord next() throws IOException {
            synchronized (ChatArchive.this) {
                if (closed) throw new IOException("历史快照已失效，请重新加载或导出");
                if (personalHead == null) personalHead = nextVisible(personal, false);
                if (sharedHead == null && shared != null) sharedHead = nextVisible(shared, true);
                if (personalHead != null && (sharedHead == null || !sentAt(personalHead).isAfter(sentAt(sharedHead)))) {
                    ChatRecord result = personalHead; personalHead = null; return result;
                }
                ChatRecord result = sharedHead; sharedHead = null; return result;
            }
        }

        private ChatRecord nextVisible(Journal source, boolean publicOnly) throws IOException {
            ChatRecord record;
            while ((record = source.next()) != null) {
                boolean publicMessage = ConversationScope.PUBLIC.equals(record.conversationId());
                if (publicOnly && (!publicMessage || record.recipient() != null || !"GROUP_MESSAGE".equals(record.messageType())))
                    throw new IOException("公共聊天室归档损坏");
                if (scope != null && !scope.equals(record.conversationId())) continue;
                if (!publicMessage || (sentAt(record).isAfter(clearedThrough) && !deletion.hiddenIds().contains(record.messageId())))
                    return record;
            }
            return null;
        }

        @Override public void close() throws IOException {
            synchronized (ChatArchive.this) {
                if (closed) return; closed = true;
                for (Set<Cursor> set : cursors.values()) set.remove(this);
                cursors.entrySet().removeIf(entry -> entry.getValue().isEmpty());
                try (personal; shared) { /* Release both snapshot handles. */ }
            }
        }
    }

    private static Instant sentAt(ChatRecord record) throws IOException {
        try { return Instant.parse(record.sentAt()); }
        catch (RuntimeException ex) { throw new IOException("历史时间无效", ex); }
    }

    private final class Journal implements AutoCloseable {
        final RandomAccessFile file;
        final long end;

        Journal(Path path) throws IOException {
            end = Files.exists(path) ? Files.size(path) : 0;
            file = end == 0 ? null : new RandomAccessFile(path.toFile(), "r");
        }

        ChatRecord next() throws IOException {
            if (file == null || file.getFilePointer() >= end) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int b;
            while (file.getFilePointer() < end && (b = file.read()) != '\n') {
                if (b == -1 || bytes.size() >= MessageCodec.MAX_FRAME_BYTES) throw new IOException("历史文件损坏");
                bytes.write(b);
            }
            try {
                ChatRecord r = gson.fromJson(bytes.toString(StandardCharsets.UTF_8), ChatRecord.class);
                if (r == null || r.messageId() == null || r.content() == null || r.sentAt() == null) throw new IOException("历史文件损坏");
                return r;
            } catch (RuntimeException ex) { throw new IOException("历史文件损坏", ex); }
        }
        @Override public void close() throws IOException {
            if (file != null) file.close();
        }
    }
}
