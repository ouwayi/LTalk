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
    private final Path groupDirectory;
    private final Gson gson = new Gson();
    private Instant last = Instant.MIN;
    private final Map<String, Set<Cursor>> cursors = new HashMap<>();
    private SocialStore social;
    ChatArchive(Path directory) throws IOException {
        this.directory = directory; Files.createDirectories(directory);
        publicJournal = directory.resolve("public.jsonl");
        groupDirectory = directory.resolve("groups");
        Files.createDirectories(groupDirectory);
        Path clock = directory.resolve("last-time");
        if (Files.exists(clock)) {
            try { last = Instant.parse(Files.readString(clock)); }
            catch (RuntimeException ex) { throw new IOException("历史时间元数据损坏", ex); }
        }
    }
    void social(SocialStore value) { social = value; }
    Set<String> groupScopes(String user) { return social.groupScopes(user); }
    synchronized String lastTime() { return last.toString(); }
    synchronized String groupHistoryStart(String id) throws IOException {
        ChatRecord oldest = null;
        try (Journal rows = new Journal(groupJournal(id), true)) {
            for (int i = 0; i < HistoryQuery.GROUP_JOIN_LIMIT; i++) {
                ChatRecord row = rows.next();
                if (row == null) break;
                oldest = row;
            }
        }
        return oldest == null ? last.plusNanos(1).toString() : oldest.sentAt();
    }

    private Path groupJournal(String id) { return groupDirectory.resolve(id + ".jsonl"); }
    synchronized Message record(MessageType type, String sender, String nickname, Message request,
                                Collection<String> recipients) throws IOException {
        Instant now = Instant.now();
        if (!now.isAfter(last)) now = last.plusNanos(1);
        Files.writeString(directory.resolve("last-time"), now.toString());
        last = now;
        boolean groupMessage = type == MessageType.GROUP_MESSAGE;
        String conversation = groupMessage ? Objects.requireNonNullElse(request.conversationId(), ConversationScope.PUBLIC)
                : ConversationScope.privateChat(sender, request.to());
        String id = request.attachment() == null ? UUID.randomUUID().toString() : request.messageId();
        ChatRecord record = new ChatRecord(id, conversation, sender,
                groupMessage ? null : request.to(), request.content(), type.name(), now.toString(), request.replyTo(),
                request.attachment());
        byte[] line = (gson.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
        if (groupMessage) {
            Path journal = ConversationScope.PUBLIC.equals(conversation) ? publicJournal : groupJournal(ConversationScope.groupId(conversation));
            // Keep the previous readable prefix if an append fails partway.
            try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
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
                .content(record.content()).serverTime(record.sentAt()).messageId(id).conversationId(conversation)
                .replyTo(record.replyTo()).attachment(record.attachment()).build();
    }

    void requireVisibleReply(String user, String scope, String replyTo) throws IOException {
        if (replyTo == null) return;
        if (visibleRecord(user, scope, replyTo) == null)
            throw new ProtocolException("NOT_FOUND", "引用的原消息在当前会话中不可查看");
    }

    ChatRecord visibleRecord(String user, String scope, String id) throws IOException {
        ConversationScope.uuid(id);
        try (Cursor rows = openNewest(user, scope)) {
            ChatRecord row;
            while ((row = rows.next()) != null) {
                if (id.equals(row.messageId())) return row;
            }
        }
        return null;
    }
    synchronized Cursor open(String user) throws IOException {
        return open(user, null);
    }
    synchronized Cursor open(String user, String scope) throws IOException {
        return open(user, scope, false, true);
    }
    synchronized Cursor openNewest(String user, String scope) throws IOException {
        return open(user, scope, true, true);
    }
    synchronized Cursor openPersonalNewest(String user) throws IOException {
        return open(user, null, true, false);
    }
    private Cursor open(String user, String scope, boolean newestFirst, boolean includeShared) throws IOException {
        ConversationScope.validate(user, scope);
        Cursor cursor = snapshot(user, scope, newestFirst, includeShared);
        cursors.computeIfAbsent(user, key -> new HashSet<>()).add(cursor); return cursor;
    }

    private Path accountJournal(String user) {
        return directory.resolve(AccountStore.fileKey(user) + ".jsonl");
    }

    private Cursor snapshot(String user, String scope, boolean newestFirst, boolean includeShared) throws IOException {
        if (ConversationScope.isGroup(scope)) {
            String group = ConversationScope.groupId(scope);
            List<SocialStore.Range> ranges = social.ranges(group, user);
            if (ranges.isEmpty()) throw new ProtocolException("FORBIDDEN", "你没有此群的历史权限");
            PublicDeletion deletion = readDeletion(deletionFile(user, scope));
            return new Cursor(new Journal(groupJournal(group), newestFirst), null, scope, deletion, newestFirst, ranges);
        }
        boolean includePublic = scope == null || ConversationScope.PUBLIC.equals(scope);
        PublicDeletion deletion = includePublic ? readDeletion(deletionFile(user, ConversationScope.PUBLIC)) : PublicDeletion.NONE;
        Journal personal = new Journal(accountJournal(user), newestFirst);
        try {
            Journal shared = includePublic && includeShared ? new Journal(publicJournal, newestFirst) : null;
            return new Cursor(personal, shared, scope, deletion, newestFirst, List.of());
        } catch (IOException ex) {
            try { personal.close(); }
            catch (IOException close) { ex.addSuppressed(close); }
            throw ex;
        }
    }

    private record PublicDeletion(String clearedThrough, Set<String> hiddenIds) {
        static final PublicDeletion NONE = new PublicDeletion(null, Set.of());
    }

    private record TimeRange(Instant start, Instant end) {
        TimeRange(SocialStore.Range range) { this(Instant.parse(range.start()), range.end() == null ? null : Instant.parse(range.end())); }
        boolean includes(Instant time) { return !time.isBefore(start) && (end == null || !time.isAfter(end)); }
    }

    private Path deletionFile(String user, String scope) throws ProtocolException {
        String suffix = ConversationScope.PUBLIC.equals(scope) ? "public" : ConversationScope.groupId(scope);
        return directory.resolve(AccountStore.fileKey(user) + "." + suffix + "-deletions.json");
    }

    private PublicDeletion readDeletion(Path path) throws IOException {
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
        invalidate(user);
        if (ConversationScope.PUBLIC.equals(scope) || ConversationScope.isGroup(scope)) {
            deleteShared(user, scope, id);
            return;
        }
        Path original = accountJournal(user);
        Path temporary = Files.createTempFile(directory, ".history-delete-", ".tmp"); boolean found = false;
        try {
            try (Journal input = new Journal(original, false);
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

    synchronized void invalidate(String user) throws IOException {
        Set<Cursor> active = cursors.remove(user);
        if (active != null) for (Cursor cursor : List.copyOf(active)) cursor.close();
    }

    private void deleteShared(String user, String scope, String id) throws IOException {
        PublicDeletion updated;
        try (Cursor input = snapshot(user, scope, false, true)) {
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
            Files.move(temporary, deletionFile(user, scope), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    final class Cursor implements AutoCloseable {
        final String token = UUID.randomUUID().toString();
        final Journal personal, shared;
        final String scope;
        final PublicDeletion deletion;
        final Instant clearedThrough;
        final boolean newestFirst;
        final List<TimeRange> ranges;
        final Instant firstAuthorized;
        ChatRecord personalHead, sharedHead;
        boolean closed;
        private boolean sharedCleared;

        Cursor(Journal personal, Journal shared, String scope, PublicDeletion deletion, boolean newestFirst,
               List<SocialStore.Range> ranges) {
            this.personal = personal; this.shared = shared; this.scope = scope; this.deletion = deletion;
            this.newestFirst = newestFirst;
            this.ranges = ranges.stream().map(TimeRange::new).toList();
            firstAuthorized = this.ranges.stream().map(TimeRange::start).min(Comparator.naturalOrder()).orElse(Instant.MIN);
            clearedThrough = deletion.clearedThrough() == null ? Instant.MIN : Instant.parse(deletion.clearedThrough());
        }

        synchronized ChatRecord next() throws IOException {
            if (closed) throw new IOException("历史快照已失效，请重新加载或导出");
            if (personalHead == null) personalHead = nextVisible(personal, false);
            if (sharedHead == null && shared != null) sharedHead = nextVisible(shared, true);
            if (personalHead != null && (sharedHead == null || personalFirst())) {
                ChatRecord result = personalHead; personalHead = null; return result;
            }
            ChatRecord result = sharedHead; sharedHead = null; return result;
        }

        private boolean personalFirst() throws IOException {
            int order = sentAt(personalHead).compareTo(sentAt(sharedHead));
            return newestFirst ? order > 0 : order <= 0;
        }

        private ChatRecord nextVisible(Journal source, boolean publicOnly) throws IOException {
            if (publicOnly && sharedCleared) return null;
            ChatRecord record;
            while ((record = source.next()) != null) {
                if (!ranges.isEmpty()) {
                    if (!scope.equals(record.conversationId()) || record.recipient() != null || !"GROUP_MESSAGE".equals(record.messageType()))
                        throw new IOException("群组归档损坏");
                    Instant time = sentAt(record);
                    if (newestFirst && time.isBefore(firstAuthorized)) return null;
                    if (time.isAfter(clearedThrough) && !deletion.hiddenIds().contains(record.messageId())
                            && ranges.stream().anyMatch(range -> range.includes(time))) return record;
                    continue;
                }
                boolean publicMessage = ConversationScope.PUBLIC.equals(record.conversationId());
                if (publicOnly && (!publicMessage || record.recipient() != null || !"GROUP_MESSAGE".equals(record.messageType())))
                    throw new IOException("公共聊天室归档损坏");
                if (scope != null && !scope.equals(record.conversationId())) continue;
                if (!publicMessage) return record;
                if (sentAt(record).isAfter(clearedThrough)) {
                    if (!deletion.hiddenIds().contains(record.messageId())) return record;
                } else if (publicOnly && newestFirst) {
                    sharedCleared = true;
                    return null;
                }
            }
            return null;
        }

        @Override public void close() throws IOException {
            synchronized (ChatArchive.this) {
                synchronized (this) {
                    if (closed) return; closed = true;
                    for (Set<Cursor> set : cursors.values()) set.remove(this);
                    cursors.entrySet().removeIf(entry -> entry.getValue().isEmpty());
                    try (personal; shared) { /* Release both snapshot handles. */ }
                }
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
        private final byte[] block, reverseLine;
        private long position, blockStart = -1;

        Journal(Path path, boolean newestFirst) throws IOException {
            end = Files.exists(path) ? Files.size(path) : 0;
            file = end == 0 ? null : new RandomAccessFile(path.toFile(), "r");
            position = end;
            block = newestFirst && end > 0 ? new byte[32 * 1024] : null;
            reverseLine = block == null ? null : new byte[MessageCodec.MAX_FRAME_BYTES];
        }

        ChatRecord next() throws IOException {
            if (block != null) return previous();
            if (file == null || file.getFilePointer() >= end) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int b;
            while (file.getFilePointer() < end && (b = file.read()) != '\n') {
                if (b == -1 || bytes.size() >= MessageCodec.MAX_FRAME_BYTES) throw new IOException("历史文件损坏");
                bytes.write(b);
            }
            return parse(bytes.toString(StandardCharsets.UTF_8));
        }

        private ChatRecord previous() throws IOException {
            if (position == 0) return null;
            if (byteAt(position - 1) == '\n') position--;
            int start = reverseLine.length;
            while (position > 0) {
                byte value = byteAt(position - 1);
                if (value == '\n') break;
                if (start == 0) throw new IOException("历史文件损坏");
                reverseLine[--start] = value;
                position--;
            }
            return parse(new String(reverseLine, start, reverseLine.length - start, StandardCharsets.UTF_8));
        }

        private byte byteAt(long offset) throws IOException {
            long start = offset - offset % block.length;
            if (start != blockStart) {
                file.seek(start);
                file.readFully(block, 0, (int) Math.min(block.length, end - start));
                blockStart = start;
            }
            return block[(int) (offset - start)];
        }

        private ChatRecord parse(String line) throws IOException {
            try {
                ChatRecord r = gson.fromJson(line, ChatRecord.class);
                if (r == null || r.messageId() == null || r.content() == null || r.sentAt() == null) throw new IOException("历史文件损坏");
                return r;
            } catch (RuntimeException ex) { throw new IOException("历史文件损坏", ex); }
        }
        @Override public void close() throws IOException {
            if (file != null) file.close();
        }
    }
}
