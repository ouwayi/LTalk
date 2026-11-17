package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
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
        try (ChatArchive.Cursor input = archive.openNewest(user, scope)) {
            if (mode == HistoryQuery.CONTEXT) return context(input, anchor);
            if (cursor != null) find(input, cursor, null);
            return messages(input, query);
        }
    }

    private Page summaries(String user, String after) throws IOException {
        // Keep only one row per conversation and bound the page even for a large contact list.
        TreeMap<String, ChatRecord> rows = new TreeMap<>();
        if (after == null || ConversationScope.PUBLIC.compareTo(after) > 0) {
            try (ChatArchive.Cursor recent = archive.openNewest(user, ConversationScope.PUBLIC)) {
                ChatRecord latest = recent.next();
                if (latest != null) rows.put(ConversationScope.PUBLIC, latest);
            }
        }
        for (String scope : archive.groupScopes(user)) {
            if (after != null && scope.compareTo(after) <= 0) continue;
            try (ChatArchive.Cursor input = archive.openNewest(user, scope)) {
                ChatRecord newest = input.next();
                if (newest != null) addSummary(rows, newest);
            }
        }
        try (ChatArchive.Cursor personal = archive.openPersonalNewest(user)) {
            ChatRecord row;
            while ((row = personal.next()) != null) {
                String scope = row.conversationId();
                if (ConversationScope.PUBLIC.equals(scope) || (after != null && scope.compareTo(after) <= 0)) continue;
                addSummary(rows, row);
            }
        }
        boolean more = rows.size() > HistoryQuery.PAGE_SIZE;
        if (more) rows.pollLastEntry();
        return new Page(List.copyOf(rows.values()), more ? rows.lastKey() : null);
    }

    private static void addSummary(TreeMap<String, ChatRecord> rows, ChatRecord row) {
        rows.putIfAbsent(row.conversationId(), row);
        if (rows.size() > HistoryQuery.PAGE_SIZE + 1) rows.pollLastEntry();
    }

    private Page messages(ChatArchive.Cursor input, String query) throws IOException {
        String needle = query == null ? null : query.strip().toLowerCase(Locale.ROOT);
        Map<String, String> names = new HashMap<>();
        List<ChatRecord> rows = new ArrayList<>();
        ChatRecord row;
        while ((row = input.next()) != null) {
            if (needle != null && !matches(row, needle, names)) continue;
            if (rows.size() == HistoryQuery.PAGE_SIZE) {
                return new Page(rows, rows.get(rows.size() - 1).messageId());
            }
            rows.add(row);
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

    private Page context(ChatArchive.Cursor input, String id) throws IOException {
        Deque<ChatRecord> newer = new ArrayDeque<>();
        ChatRecord target = find(input, id, newer);
        List<ChatRecord> rows = new ArrayList<>(newer);
        rows.add(target);
        for (int i = 0; i < HistoryQuery.CONTEXT_RADIUS; i++) {
            ChatRecord row = input.next();
            if (row == null) break;
            rows.add(row);
        }
        return new Page(rows, null);
    }

    private ChatRecord find(ChatArchive.Cursor input, String id, Deque<ChatRecord> newer) throws IOException {
        ChatRecord row;
        while ((row = input.next()) != null) {
            if (id.equals(row.messageId())) return row;
            if (newer != null) {
                newer.addLast(row);
                if (newer.size() > HistoryQuery.CONTEXT_RADIUS) newer.removeFirst();
            }
        }
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
