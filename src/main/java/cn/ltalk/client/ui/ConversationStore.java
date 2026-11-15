package cn.ltalk.client.ui;

import cn.ltalk.common.Message;
import cn.ltalk.common.MessageType;
import cn.ltalk.common.ChatRecord;
import cn.ltalk.common.ConversationScope;

import java.time.Instant;
import java.util.*;

/** EDT-confined presentation state; no sockets, protocol changes, persistence or delivery claims. */
public final class ConversationStore {
    public enum EntryKind { MESSAGE, SYSTEM, ERROR }
    public record Entry(EntryKind kind, String from, String to, String content, Instant time, String messageId, String nickname) {
        public Entry(EntryKind kind, String from, String to, String content, Instant time) { this(kind, from, to, content, time, null); }
        public Entry(EntryKind kind, String from, String to, String content, Instant time, String messageId) {
            this(kind, from, to, content, time, messageId, null);
        }
        public String displayName() {
            return nickname == null || nickname.isBlank() ? from : nickname.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        }
    }
    public record Summary(ConversationId id, String title, String preview, Instant time, int unread, boolean online) { }

    private static final class Conversation {
        final ConversationId id;
        final String title;
        final List<Entry> entries = new ArrayList<>();
        String draft = "";
        boolean online;
        int unread;
        Instant lastMessage;
        String preview = "";
        Conversation(ConversationId id, String title) { this.id = id; this.title = title; }
        Summary summary() { return new Summary(id, title, preview, lastMessage, unread, online); }
    }

    private final Map<ConversationId, Conversation> conversations = new LinkedHashMap<>();
    private final Set<String> messageIds = new HashSet<>();
    private final Set<String> liveIds = new HashSet<>();
    private String self = "";
    private ConversationId selected = ConversationId.PUBLIC;
    private boolean visible = true;
    private boolean selectionConfirmed = true;
    private List<String> onlineUsers = List.of();

    public ConversationStore() { reset(); }
    private void reset() {
        conversations.clear();
        messageIds.clear();
        liveIds.clear();
        Conversation group = new Conversation(ConversationId.PUBLIC, "公共聊天室");
        group.preview = "和局域网里的伙伴开始聊天";
        conversations.put(group.id, group);
        selected = group.id; selectionConfirmed = true; onlineUsers = List.of();
    }

    /** Switching identities must never expose the previous identity's private history or drafts. */
    public void useIdentity(String userId) {
        if (!self.equals(userId)) { reset(); self = userId; }
    }
    public String self() { return self; }
    public ConversationId selectedId() { return selected; }
    public Summary selected() { return conversations.get(selected).summary(); }
    public List<Entry> entries() { return List.copyOf(conversations.get(selected).entries); }
    public int onlineCount() { return onlineUsers.size(); }
    public String draft() { return conversations.get(selected).draft; }
    public void saveDraft(String text) { conversations.get(selected).draft = text; }

    public void select(ConversationId id) {
        Conversation conversation = conversations.get(id);
        if (conversation == null) throw new IllegalArgumentException("会话不存在");
        selected = id;
        selectionConfirmed = id.kind() == ConversationId.Kind.GROUP || conversation.online;
        if (visible) conversation.unread = 0;
    }

    public void setVisible(boolean value) {
        visible = value;
        if (visible) conversations.get(selected).unread = 0;
    }

    public boolean canSend() {
        Conversation current = conversations.get(selected);
        return selectionConfirmed && (selected.kind() == ConversationId.Kind.GROUP || current.online);
    }

    public String targetLabel() {
        if (selected.kind() == ConversationId.Kind.GROUP) return "群聊 · 所有在线用户";
        if (!selectionConfirmed && conversations.get(selected).online) return "私聊 " + selected.key() + " · 已上线，请重新选择会话";
        if (!canSend()) return "私聊 " + selected.key() + " · 对象已离线，请重新选择会话";
        return "私聊 " + selected.key() + " · 在线，仅双方可见";
    }

    public boolean updateUsers(List<String> users) {
        onlineUsers = List.copyOf(users);
        Conversation current = conversations.get(selected);
        boolean invalidated = selected.kind() == ConversationId.Kind.PRIVATE && current.online && !users.contains(selected.key());
        for (Conversation conversation : conversations.values()) conversation.online = false;
        conversations.get(ConversationId.PUBLIC).online = !users.isEmpty();
        for (String name : users) {
            if (!name.equals(self)) ensurePrivate(name).online = true;
        }
        if (invalidated) selectionConfirmed = false;
        return invalidated;
    }

    private Conversation ensurePrivate(String user) {
        ConversationId id = ConversationId.privateWith(user);
        return conversations.computeIfAbsent(id, key -> new Conversation(key, user));
    }

    public ConversationId receive(Message message) {
        return receive(message, false);
    }
    public void beginHistory() {
        messageIds.clear();
        for (Conversation conversation : conversations.values()) {
            conversation.entries.removeIf(e -> e.kind() == EntryKind.MESSAGE);
            updateSummary(conversation);
        }
    }
    public ConversationId restore(ChatRecord record) {
        return restore(record, null);
    }
    public ConversationId restore(ChatRecord record, String nickname) {
        return receive(Message.of(MessageType.valueOf(record.messageType())).messageId(record.messageId()).from(record.sender())
                .to(record.recipient()).content(record.content()).serverTime(record.sentAt()).nickname(nickname).build(), true);
    }
    private ConversationId receive(Message message, boolean historical) {
        boolean group = message.type() == MessageType.GROUP_MESSAGE;
        String peer = self.equals(message.from()) ? message.to() : message.from();
        Conversation conversation = group ? conversations.get(ConversationId.PUBLIC) : ensurePrivate(peer);
        boolean firstLive = !historical && (message.messageId() == null || liveIds.add(message.messageId()));
        if (firstLive && !self.equals(message.from()) && !(visible && selected.equals(conversation.id))) conversation.unread++;
        if (message.messageId() != null && !messageIds.add(message.messageId())) return conversation.id;
        Instant time = parseTime(message.serverTime());
        Entry entry = new Entry(EntryKind.MESSAGE, message.from(), message.to(), message.content(), time, message.messageId(), message.nickname());
        int index = Collections.binarySearch(conversation.entries, entry, Comparator.comparing(Entry::time));
        if (index < 0) index = -index - 1;
        else while (index < conversation.entries.size() && !conversation.entries.get(index).time().isAfter(time)) index++;
        conversation.entries.add(index, entry);
        if (conversation.lastMessage == null || !time.isBefore(conversation.lastMessage)) {
            conversation.lastMessage = time;
            conversation.preview = (self.equals(message.from()) ? "你：" : group ? message.from() + "：" : "") + message.content();
        }
        return conversation.id;
    }
    public void deleteHistory(String scope, String id) {
        ConversationId target = ConversationScope.PUBLIC.equals(scope) ? ConversationId.PUBLIC
                : ConversationId.privateWith(Arrays.stream(scope.split(":" )).skip(1).filter(p -> !p.equals(self)).findFirst().orElseThrow());
        Conversation conversation = conversations.get(target); if (conversation == null) return;
        conversation.entries.removeIf(entry -> {
            boolean remove = id == null || id.equals(entry.messageId());
            if (remove && entry.messageId() != null) { messageIds.remove(entry.messageId()); liveIds.remove(entry.messageId()); } return remove;
        });
        conversation.unread = Math.min(conversation.unread, (int) conversation.entries.stream().filter(e -> e.kind() == EntryKind.MESSAGE && !self.equals(e.from())).count());
        updateSummary(conversation);
    }
    private void updateSummary(Conversation conversation) {
        Entry last = null;
        for (Entry entry : conversation.entries) if (entry.kind() == EntryKind.MESSAGE) last = entry;
        conversation.lastMessage = last == null ? null : last.time();
        conversation.preview = last == null ? "" : (self.equals(last.from()) ? "你：" : conversation.id.kind() == ConversationId.Kind.GROUP ? last.from() + "：" : "") + last.content();
    }

    public void system(String text) {
        conversations.get(ConversationId.PUBLIC).entries.add(new Entry(EntryKind.SYSTEM, "", "", text, Instant.now()));
    }
    public ConversationId error(Message message) {
        // The existing network API returns no submission ID. An uncorrelated error is shown in the
        // current view and global status, never fabricated as a successfully delivered bubble.
        ConversationId id = selected;
        notice(id, message.code() + "：" + message.message(), true);
        return id;
    }
    public void notice(ConversationId id, String text, boolean error) {
        conversations.get(id).entries.add(new Entry(error ? EntryKind.ERROR : EntryKind.SYSTEM, "", "", text, Instant.now()));
    }

    public List<Summary> summaries(String query) {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return conversations.values().stream()
                .filter(c -> needle.isEmpty() || c.title.toLowerCase(Locale.ROOT).contains(needle)
                        || c.preview.toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.<Conversation>comparingInt(c -> c.id.equals(ConversationId.PUBLIC) ? 0 : 1)
                        .thenComparing(c -> c.lastMessage, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(c -> c.title))
                .map(Conversation::summary).toList();
    }

    private static Instant parseTime(String value) {
        try { return Instant.parse(value); } catch (RuntimeException ex) { return Instant.now(); }
    }
}
