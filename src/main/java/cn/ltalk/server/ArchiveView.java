package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

final class ArchiveView {
    record Page(List<ChatRecord> rows, String next) { }

    private final ChatArchive archive;
    private final AccountStore accounts;

    ArchiveView(ChatArchive archive, AccountStore accounts) {
        this.archive = archive;
        this.accounts = accounts;
    }

    Page read(String user, HistoryQuery mode, Message request) throws IOException {
        String scope = request.conversationId(), cursor = request.cursor(), anchor = request.messageId();
        String query = request.content();
        if (mode == HistoryQuery.SUMMARIES) {
            if (scope != null || query != null || anchor != null) throw invalid();
            ConversationScope.validate(user, cursor);
            return summaries(user, cursor);
        }
        if (scope == null) throw invalid();
        ConversationScope.validate(user, scope);
        if (mode == HistoryQuery.CONTEXT) {
            if (cursor != null || query != null || anchor == null) throw invalid();
            validateId(anchor);
        } else {
            if (anchor != null) throw invalid();
            if (cursor != null) validateId(cursor);
            if (mode == HistoryQuery.SEARCH) {
                if (query == null || query.isBlank() || query.length() > HistoryQuery.QUERY_LIMIT) throw invalid();
            } else if (query != null) throw invalid();
        }
        return archive.read(user, scope, view -> {
            if (mode == HistoryQuery.CONTEXT) return context(view, find(view, anchor));
            Long sequence = cursor == null ? null : find(view, cursor).sequence();
            return messages(view, sequence, query);
        });
    }

    private Page summaries(String user, String after) throws IOException {
        List<ChatRecord> rows = archive.summaries(user, after);
        boolean more = rows.size() > HistoryQuery.PAGE_SIZE;
        if (more) rows.remove(rows.size() - 1);
        return new Page(rows, more ? rows.get(rows.size() - 1).conversationId() : null);
    }

    private Page messages(ChatArchive.VisibleHistory view, Long anchor, String query) throws SQLException {
        String needle = query == null ? null : query.strip().toLowerCase(Locale.ROOT);
        Map<String, String> names = new HashMap<>();
        List<ChatRecord> rows = new ArrayList<>();
        int limit = needle == null ? HistoryQuery.PAGE_SIZE + 1 : -1;
        try (var statement = view.page(true, anchor, Long.MAX_VALUE, limit);
             ResultSet input = statement.executeQuery()) {
            while (input.next()) {
                ChatRecord row = ChatArchive.record(input);
                if (needle != null && !matches(row, needle, names)) continue;
                if (rows.size() == HistoryQuery.PAGE_SIZE) {
                    return new Page(rows, rows.get(rows.size() - 1).messageId());
                }
                rows.add(row);
            }
        }
        return new Page(rows, null);
    }

    private boolean matches(ChatRecord record, String needle, Map<String, String> names) {
        return record.content().toLowerCase(Locale.ROOT).contains(needle)
                || record.sender().toLowerCase(Locale.ROOT).contains(needle)
                || names.computeIfAbsent(record.sender(), user -> {
                    String nickname = accounts.nickname(user);
                    return nickname == null ? "" : nickname.toLowerCase(Locale.ROOT);
                }).contains(needle);
    }

    private Page context(ChatArchive.VisibleHistory view, ChatArchive.Entry target) throws SQLException {
        List<ChatRecord> rows = new ArrayList<>();
        try (var query = view.page(false, target.sequence(), Long.MAX_VALUE, HistoryQuery.CONTEXT_RADIUS);
             ResultSet newer = query.executeQuery()) {
            while (newer.next()) rows.add(ChatArchive.record(newer));
        }
        Collections.reverse(rows);
        rows.add(target.record());
        try (var query = view.page(true, target.sequence(), Long.MAX_VALUE, HistoryQuery.CONTEXT_RADIUS);
             ResultSet older = query.executeQuery()) {
            while (older.next()) rows.add(ChatArchive.record(older));
        }
        return new Page(rows, null);
    }

    private ChatArchive.Entry find(ChatArchive.VisibleHistory view, String id) throws SQLException, ProtocolException {
        ChatArchive.Entry entry = view.find(id);
        if (entry != null) return entry;
        throw new ProtocolException("NOT_FOUND", "此会话中的消息已不可见，请重新加载历史");
    }

    private static void validateId(String id) throws ProtocolException {
        try {
            if (UUID.fromString(id).toString().equals(id)) return;
        } catch (IllegalArgumentException ignored) { }
        throw invalid();
    }

    private static ProtocolException invalid() {
        return new ProtocolException("BAD_MESSAGE", "无效的历史浏览参数");
    }
}
