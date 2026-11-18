package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static cn.ltalk.server.SqliteDatabase.execute;
import static cn.ltalk.server.SqliteDatabase.prepare;

/** Stores each message once and applies the same visibility rules to every archive consumer. */
final class ChatArchive {
    private final SqliteDatabase database;
    private final Map<String, Set<Cursor>> cursors = new HashMap<>();
    private long lastSequence;
    private Instant lastTime = Instant.MIN;
    private volatile long recallRevision;

    ChatArchive(SqliteDatabase database) throws IOException {
        this.database = database;
        database.read(connection -> {
            try (Statement query = connection.createStatement();
                 ResultSet rows = query.executeQuery("SELECT seq, sent_at FROM messages ORDER BY seq DESC LIMIT 1")) {
                if (rows.next()) {
                    lastSequence = rows.getLong(1);
                    lastTime = Instant.parse(rows.getString(2));
                }
            }
            return null;
        });
    }

    synchronized long lastSequence() { return lastSequence; }
    long recallRevision() { return recallRevision; }

    synchronized long groupHistoryStart(String id) throws IOException {
        return database.read(connection -> {
            try (PreparedStatement query = prepare(connection, """
                    SELECT MIN(seq) FROM (
                        SELECT seq FROM messages WHERE conversation_id = ? ORDER BY seq DESC LIMIT ?
                    )
                    """, ConversationScope.group(id), HistoryQuery.GROUP_JOIN_LIMIT);
                 ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing group history boundary");
                long start = rows.getLong(1);
                return rows.wasNull() ? lastSequence + 1 : start;
            }
        });
    }

    synchronized Message record(MessageType type, String sender, String nickname, Message request) throws IOException {
        Instant now = Instant.now();
        Instant sentAt = now.isAfter(lastTime) ? now : lastTime.plusNanos(1);
        long sequence = lastSequence + 1;
        boolean groupMessage = type == MessageType.GROUP_MESSAGE;
        String scope = groupMessage ? Objects.requireNonNullElse(request.conversationId(), ConversationScope.PUBLIC)
                : ConversationScope.privateChat(sender, request.to());
        String group = ConversationScope.isGroup(scope) ? ConversationScope.groupId(scope) : null;
        String recipient = groupMessage ? null : request.to();
        Attachment attachment = request.attachment();
        String id = attachment == null ? UUID.randomUUID().toString() : request.messageId();
        database.write(connection -> {
            execute(connection, """
                    INSERT INTO messages (seq, message_id, conversation_id, group_id, sender, recipient, content, sent_at,
                                          reply_to, attachment_kind, attachment_name, attachment_size, thumbnail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, sequence, id, scope, group, sender, recipient, request.content(), sentAt.toString(),
                    request.replyTo(), attachment == null ? null : attachment.kind().name(),
                    attachment == null ? null : attachment.name(), attachment == null ? null : attachment.size(),
                    attachment == null ? null : attachment.thumbnail());
            if (!groupMessage) {
                execute(connection, "INSERT INTO private_visibility (user_id, message_seq) VALUES (?, ?), (?, ?)",
                        sender, sequence, recipient, sequence);
            }
            return null;
        });
        lastSequence = sequence;
        lastTime = sentAt;
        return Message.of(type).requestId(request.requestId()).from(sender).nickname(nickname).to(recipient)
                .content(request.content()).serverTime(sentAt.toString()).messageId(id).conversationId(scope)
                .replyTo(request.replyTo()).attachment(attachment).build();
    }

    void requireVisibleReply(String user, String scope, String replyTo) throws IOException {
        if (replyTo != null) {
            ChatRecord original = visibleRecord(user, scope, replyTo);
            if (original == null || original.isRecalled())
                throw new ProtocolException("NOT_FOUND", "引用的原消息在当前会话中不可查看");
        }
    }

    record Recall(ChatRecord record, Set<String> groupAudience, boolean changed) { }

    synchronized Recall recall(String user, String scope, String id, Instant now) throws IOException {
        if (scope == null) throw new ProtocolException("BAD_MESSAGE", "撤回必须指定会话");
        ConversationScope.validate(user, scope);
        ConversationScope.uuid(id);
        Recall result = database.write(connection -> {
            ChatRecord original;
            long sequence;
            try (PreparedStatement query = prepare(connection, """
                    SELECT * FROM messages WHERE message_id = ? AND conversation_id = ? AND sender = ?
                    """, id, scope, user);
                 ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new ProtocolException("NOT_FOUND", "此会话中没有可撤回的本人消息");
                original = record(rows);
                sequence = rows.getLong("seq");
            }
            if (original.isRecalled()) return new Recall(original, Set.of(), false);
            Duration age = Duration.between(Instant.parse(original.sentAt()), now);
            if (age.isNegative() || age.compareTo(Duration.ofSeconds(ChatRecord.RECALL_SECONDS)) > 0)
                throw new ProtocolException("RECALL_EXPIRED", "只能撤回发送后 60 秒内的消息");
            Set<String> audience = new HashSet<>();
            if (ConversationScope.isGroup(scope)) {
                try (PreparedStatement query = prepare(connection, """
                        SELECT DISTINCT user_id FROM group_memberships
                        WHERE group_id = ? AND start_seq <= ? AND (end_seq IS NULL OR end_seq >= ?)
                        """, ConversationScope.groupId(scope), sequence, sequence);
                     ResultSet rows = query.executeQuery()) {
                    while (rows.next()) audience.add(rows.getString(1));
                }
            }
            execute(connection, """
                    UPDATE messages SET recalled = 1, content = '', reply_to = NULL, attachment_kind = NULL,
                                        attachment_name = NULL, attachment_size = NULL, thumbnail = NULL
                    WHERE seq = ?
                    """, sequence);
            return new Recall(original.asRecalled(), audience, true);
        });
        if (result.changed()) {
            recallRevision++;
            for (Set<Cursor> active : cursors.values())
                for (Cursor cursor : active) cursor.replaceRecalled(result.record());
        }
        return result;
    }

    // History views may have waited to send display profiles while a recall committed.
    List<ChatRecord> refreshRecalls(List<ChatRecord> records) throws IOException {
        return database.read(connection -> {
            List<ChatRecord> updated = new ArrayList<>(records.size());
            try (PreparedStatement query = connection.prepareStatement("SELECT recalled FROM messages WHERE message_id = ?")) {
                for (ChatRecord record : records) {
                    query.setString(1, record.messageId());
                    try (ResultSet rows = query.executeQuery()) {
                        updated.add(rows.next() && rows.getBoolean(1) ? record.asRecalled() : record);
                    }
                }
            }
            return updated;
        });
    }

    ChatRecord visibleRecord(String user, String scope, String id) throws IOException {
        ConversationScope.uuid(id);
        return read(user, scope, view -> {
            Entry entry = view.find(id);
            return entry == null ? null : entry.record();
        });
    }

    @FunctionalInterface
    interface HistoryRead<T> {
        T run(VisibleHistory view) throws SQLException, IOException;
    }

    <T> T read(String user, String scope, HistoryRead<T> operation) throws IOException {
        ConversationScope.validate(user, scope);
        return database.read(connection -> operation.run(new VisibleHistory(connection, user, scope,
                membershipPeriod(connection, user, scope))));
    }

    List<ChatRecord> summaries(String user, String after) throws IOException {
        return database.read(connection -> {
            List<ChatRecord> summaries = new ArrayList<>();
            try (PreparedStatement query = prepare(connection, """
                    WITH scopes(scope) AS (
                        VALUES ('GROUP:PUBLIC')
                        UNION SELECT 'GROUP:' || group_id FROM group_memberships WHERE user_id = ?
                        UNION SELECT m.conversation_id FROM private_visibility p
                              JOIN messages m ON m.seq = p.message_seq WHERE p.user_id = ?
                    )
                    SELECT scope FROM scopes WHERE scope > ? ORDER BY scope
                    """, user, user, Objects.requireNonNullElse(after, ""));
                 ResultSet scopes = query.executeQuery()) {
                while (summaries.size() <= HistoryQuery.PAGE_SIZE && scopes.next()) {
                    String scope = scopes.getString(1);
                    VisibleHistory view = new VisibleHistory(connection, user, scope, membershipPeriod(connection, user, scope));
                    try (PreparedStatement latest = view.page(true, null, Long.MAX_VALUE, 1);
                         ResultSet rows = latest.executeQuery()) {
                        if (rows.next()) summaries.add(record(rows));
                    }
                }
            }
            return summaries;
        });
    }

    private static Integer membershipPeriod(Connection connection, String user, String scope) throws IOException, SQLException {
        if (!ConversationScope.isGroup(scope)) return null;
        try (PreparedStatement query = prepare(connection, """
                SELECT MAX(period) FROM group_memberships WHERE group_id = ? AND user_id = ?
                """, ConversationScope.groupId(scope), user);
             ResultSet rows = query.executeQuery()) {
            if (!rows.next()) throw new SQLException("Missing group membership boundary");
            int period = rows.getInt(1);
            if (rows.wasNull()) throw new ProtocolException("FORBIDDEN", "你没有此群的历史权限");
            return period;
        }
    }

    record Entry(long sequence, ChatRecord record) { }

    static final class VisibleHistory {
        private static final String PRIVATE_ACCESS = """
                EXISTS (SELECT 1 FROM private_visibility p WHERE p.user_id = ?1 AND p.message_seq = m.seq)
                """;

        private final Connection connection;
        private final String user;
        private final String scope;
        private final Integer lastPeriod;
        private final String visible;

        VisibleHistory(Connection connection, String user, String scope, Integer lastPeriod) {
            this.connection = connection;
            this.user = user;
            this.scope = scope;
            this.lastPeriod = lastPeriod;
            String access;
            if (scope == null) {
                // The original full-account operation returns the public room and private conversations.
                access = "(m.conversation_id = 'GROUP:PUBLIC' OR " + PRIVATE_ACCESS + ")";
            } else if (lastPeriod != null) {
                access = """
                        m.conversation_id = ?2 AND EXISTS (
                            SELECT 1 FROM group_memberships g WHERE g.group_id = m.group_id AND g.user_id = ?1
                            AND g.period <= ?3 AND g.start_seq <= m.seq AND (g.end_seq IS NULL OR g.end_seq >= m.seq)
                        )
                        """;
            } else {
                access = "m.conversation_id = ?2";
                if (!ConversationScope.PUBLIC.equals(scope)) access += " AND " + PRIVATE_ACCESS;
            }
            String clearScope = scope == null ? "m.conversation_id" : "?2";
            visible = "SELECT m.* FROM messages m WHERE " + access + """
                     AND m.seq > COALESCE((SELECT c.through_seq FROM history_clears c
                                          WHERE c.user_id = ?1 AND c.conversation_id =
                    """ + clearScope + """
                    ), 0)
                    AND NOT EXISTS (SELECT 1 FROM hidden_messages h WHERE h.user_id = ?1 AND h.message_seq = m.seq)
                    """;
        }

        // Positions 1–3 are the viewer, scope and group period. Query-specific bindings start at 4.
        Entry find(String id) throws SQLException {
            try (PreparedStatement query = prepare(connection, visible + " AND m.message_id = ?4", user, scope, lastPeriod, id);
                 ResultSet rows = query.executeQuery()) {
                return rows.next() ? new Entry(rows.getLong("seq"), record(rows)) : null;
            }
        }

        PreparedStatement page(boolean newest, Long anchor, long ceiling, int limit) throws SQLException {
            String order = newest ? " ORDER BY m.seq DESC LIMIT ?" : " ORDER BY m.seq LIMIT ?";
            String bounded = visible + " AND m.seq <= ?4";
            if (anchor == null) return prepare(connection, bounded + order, user, scope, lastPeriod, ceiling, limit);
            String relative = newest ? " AND m.seq < ?" : " AND m.seq > ?";
            return prepare(connection, bounded + relative + order, user, scope, lastPeriod, ceiling, anchor, limit);
        }
    }

    static ChatRecord record(ResultSet rows) throws SQLException {
        String kind = rows.getString("attachment_kind");
        Attachment attachment = kind == null ? null : new Attachment(Attachment.Kind.valueOf(kind),
                rows.getString("attachment_name"), rows.getLong("attachment_size"), rows.getString("thumbnail"))
                .withoutOriginalImageName();
        String recipient = rows.getString("recipient");
        ChatRecord record = new ChatRecord(rows.getString("message_id"), rows.getString("conversation_id"), rows.getString("sender"),
                recipient, attachment == null ? rows.getString("content") : attachment.messageContent(),
                recipient == null ? "GROUP_MESSAGE" : "PRIVATE_MESSAGE",
                rows.getString("sent_at"), rows.getString("reply_to"), attachment);
        return rows.getBoolean("recalled") ? record.asRecalled() : record;
    }

    Cursor open(String user) throws IOException { return open(user, null); }

    synchronized Cursor open(String user, String scope) throws IOException {
        ConversationScope.validate(user, scope);
        Integer period = database.read(connection -> membershipPeriod(connection, user, scope));
        Cursor cursor = new Cursor(user, scope, lastSequence, period);
        cursors.computeIfAbsent(user, key -> new HashSet<>()).add(cursor);
        return cursor;
    }

    synchronized void delete(String user, String scope, String id) throws IOException {
        if (scope == null) throw new ProtocolException("BAD_MESSAGE", "删除必须指定会话");
        ConversationScope.validate(user, scope);
        if (id != null) ConversationScope.uuid(id);
        database.write(connection -> {
            Integer period = membershipPeriod(connection, user, scope);
            if (id == null) {
                execute(connection, """
                        INSERT INTO history_clears (user_id, conversation_id, through_seq) VALUES (?, ?, ?)
                        ON CONFLICT(user_id, conversation_id) DO UPDATE SET through_seq = excluded.through_seq
                        """, user, scope, lastSequence);
                execute(connection, """
                        DELETE FROM hidden_messages WHERE user_id = ?
                            AND message_seq IN (SELECT seq FROM messages WHERE conversation_id = ?)
                        """, user, scope);
            } else {
                Entry entry = new VisibleHistory(connection, user, scope, period).find(id);
                if (entry == null) throw new ProtocolException("NOT_FOUND", "本人此会话中没有这条记录");
                execute(connection, "INSERT INTO hidden_messages (user_id, message_seq) VALUES (?, ?)", user, entry.sequence());
            }
            return null;
        });
        Set<Cursor> active = cursors.remove(user);
        if (active != null) for (Cursor cursor : active) cursor.close();
    }

    final class Cursor implements AutoCloseable {
        private static final int BATCH_SIZE = 32;
        final String token = UUID.randomUUID().toString();
        final String scope;
        private final String user;
        private final long ceiling;
        private final Integer lastPeriod;
        private final Deque<ChatRecord> batch = new ArrayDeque<>();
        private long position;
        private boolean done, closed;

        Cursor(String user, String scope, long ceiling, Integer lastPeriod) {
            this.user = user;
            this.scope = scope;
            this.ceiling = ceiling;
            this.lastPeriod = lastPeriod;
        }

        synchronized ChatRecord next() throws IOException {
            if (closed) throw new IOException("历史快照已失效，请重新加载或导出");
            if (batch.isEmpty() && !done) loadBatch();
            return batch.pollFirst();
        }

        private synchronized void replaceRecalled(ChatRecord recalled) {
            for (int i = batch.size(); i > 0; i--) {
                ChatRecord row = batch.removeFirst();
                batch.addLast(row.messageId().equals(recalled.messageId()) ? recalled : row);
            }
        }

        private void loadBatch() throws IOException {
            database.read(connection -> {
                VisibleHistory view = new VisibleHistory(connection, user, scope, lastPeriod);
                try (PreparedStatement query = view.page(false, position, ceiling, BATCH_SIZE);
                     ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        batch.addLast(record(rows));
                        position = rows.getLong("seq");
                    }
                    done = batch.size() < BATCH_SIZE;
                }
                return null;
            });
        }

        @Override public void close() {
            synchronized (ChatArchive.this) {
                synchronized (this) {
                    closed = true;
                    batch.clear();
                }
                Set<Cursor> active = cursors.get(user);
                if (active != null) {
                    active.remove(this);
                    if (active.isEmpty()) cursors.remove(user);
                }
            }
        }
    }
}
