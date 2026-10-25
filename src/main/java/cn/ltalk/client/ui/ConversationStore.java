package cn.ltalk.client.ui;

import cn.ltalk.common.Message;
import cn.ltalk.common.MessageType;

import java.time.Instant;
import java.util.*;

/** EDT-confined presentation state; no sockets, protocol changes, persistence or delivery claims. */
public final class ConversationStore {
    public enum EntryKind { MESSAGE, SYSTEM, ERROR }
    public record Entry(EntryKind kind, String from, String to, String content, Instant time) { }
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
    private String self = "";
    private ConversationId selected = ConversationId.PUBLIC;
    private boolean visible = true;
    private boolean selectionConfirmed = true;
    private List<String> onlineUsers = List.of();

    public ConversationStore() { reset(); }
    private void reset() {
        conversations.clear();
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
        boolean group = message.type() == MessageType.GROUP_MESSAGE;
        String peer = self.equals(message.from()) ? message.to() : message.from();
        Conversation conversation = group ? conversations.get(ConversationId.PUBLIC) : ensurePrivate(peer);
        Instant time = parseTime(message.serverTime());
        Entry entry = new Entry(EntryKind.MESSAGE, message.from(), message.to(), message.content(), time);
        conversation.entries.add(entry);
        conversation.lastMessage = time;
        conversation.preview = (self.equals(message.from()) ? "你：" : group ? message.from() + "：" : "") + message.content();
        boolean own = self.equals(message.from());
        if (!own && !(visible && selected.equals(conversation.id))) conversation.unread++;
        return conversation.id;
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
