package cn.ltalk.client.ui;

import cn.ltalk.client.ContactProfile;
import cn.ltalk.common.Message;
import cn.ltalk.common.MessageType;
import cn.ltalk.common.ChatRecord;
import cn.ltalk.common.ConversationScope;

import java.time.Instant;
import java.util.*;

/** EDT-confined presentation state; no sockets, protocol changes, persistence or delivery claims. */
public final class ConversationStore {
    public enum EntryKind { MESSAGE, OUTGOING, SYSTEM, ERROR }
    public enum SendStatus { SENDING, CONFIRMED, FAILED, UNCONFIRMED }
    public record Entry(EntryKind kind, String from, String to, String content, Instant time, String messageId,
                        String nickname, SendStatus sendStatus, String sendDetail) {
        public Entry(EntryKind kind, String from, String to, String content, Instant time) { this(kind, from, to, content, time, null); }
        public Entry(EntryKind kind, String from, String to, String content, Instant time, String messageId) {
            this(kind, from, to, content, time, messageId, null);
        }
        public Entry(EntryKind kind, String from, String to, String content, Instant time, String messageId, String nickname) {
            this(kind, from, to, content, time, messageId, nickname, null, null);
        }
        public String displayName() {
            return ContactProfile.displayName(from, nickname);
        }
    }
    public record Summary(ConversationId id, String title, String preview, Instant time, int unread, boolean online,
                          ContactProfile profile) { }

    private static final class Conversation {
        final ConversationId id;
        final List<Entry> entries = new ArrayList<>();
        String draft = "";
        boolean online;
        int unread;
        Entry lastMessage;
        String emptyPreview = "";
        Conversation(ConversationId id) { this.id = id; }
    }

    private final Map<ConversationId, Conversation> conversations = new LinkedHashMap<>();
    private final Map<String, ContactProfile> profiles = new HashMap<>();
    private final Set<String> messageIds = new HashSet<>();
    private final Set<String> liveIds = new HashSet<>();
    private final Map<String, Entry> outgoing = new HashMap<>();
    private final Set<String> confirmedSends = new HashSet<>();
    private String self = "";
    private ConversationId selected = ConversationId.PUBLIC;
    private boolean visible = true;
    private boolean selectionConfirmed = true;
    private boolean awaitingUsers;
    private List<String> onlineUsers = List.of();

    public ConversationStore() { reset(); }
    private void reset() {
        conversations.clear();
        clearProfiles();
        messageIds.clear();
        liveIds.clear();
        outgoing.clear();
        confirmedSends.clear();
        Conversation group = new Conversation(ConversationId.PUBLIC);
        group.emptyPreview = "和局域网里的伙伴开始聊天";
        conversations.put(group.id, group);
        selected = group.id; selectionConfirmed = true; awaitingUsers = false; onlineUsers = List.of();
    }

    /** Switching identities must never expose the previous identity's private history or drafts. */
    public void useIdentity(String userId) {
        if (!self.equals(userId)) { reset(); self = userId; }
    }
    public String self() { return self; }
    public ConversationId selectedId() { return selected; }
    public Summary selected() { return summary(conversations.get(selected)); }
    public List<Entry> entries() { return List.copyOf(conversations.get(selected).entries); }
    public int onlineCount() { return onlineUsers.size(); }
    public String draft() { return conversations.get(selected).draft; }
    public void saveDraft(String text) { conversations.get(selected).draft = text; }

    public void clearProfiles() { profiles.clear(); }
    public ContactProfile profile(String userId) { return profiles.get(userId); }
    public boolean updateProfile(ContactProfile profile) {
        ContactProfile current = profiles.get(profile.userId());
        if (current != null && current.revision() >= profile.revision()) return false;
        profiles.put(profile.userId(), profile);
        return true;
    }
    private String displayName(String userId, String fallback) {
        ContactProfile profile = profiles.get(userId);
        return profile == null ? fallback : profile.displayName();
    }
    private Summary summary(Conversation conversation) {
        boolean group = conversation.id.kind() == ConversationId.Kind.GROUP;
        String key = conversation.id.key();
        Entry last = conversation.lastMessage;
        String preview = conversation.emptyPreview;
        if (last != null) {
            String prefix = group ? displayName(last.from(), last.displayName()) + "："
                    : self.equals(last.from()) ? "你：" : "";
            preview = prefix + last.content();
        }
        return new Summary(conversation.id, group ? "公共聊天室" : displayName(key, key), preview,
                last == null ? null : last.time(), conversation.unread, conversation.online, group ? null : profiles.get(key));
    }

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
        String prefix = "私聊 " + displayName(selected.key(), selected.key());
        if (!selectionConfirmed && conversations.get(selected).online) return prefix + " · 已上线，请重新选择会话";
        if (!canSend()) return prefix + " · 对象已离线，请重新选择会话";
        return prefix + " · 在线，仅双方可见";
    }

    public boolean updateUsers(List<String> users) {
        onlineUsers = List.copyOf(users);
        Conversation current = conversations.get(selected);
        boolean invalidated = selected.kind() == ConversationId.Kind.PRIVATE && selectionConfirmed
                && (current.online || awaitingUsers) && !users.contains(selected.key());
        awaitingUsers = false;
        for (Conversation conversation : conversations.values()) conversation.online = false;
        conversations.get(ConversationId.PUBLIC).online = !users.isEmpty();
        for (String name : users) {
            if (!name.equals(self)) ensurePrivate(name).online = true;
        }
        if (invalidated) selectionConfirmed = false;
        return invalidated;
    }

    public void disconnected() {
        onlineUsers = List.of();
        for (Conversation conversation : conversations.values()) conversation.online = false;
        awaitingUsers = true;
    }

    private Conversation ensurePrivate(String user) {
        ConversationId id = ConversationId.privateWith(user);
        return conversations.computeIfAbsent(id, Conversation::new);
    }

    public ConversationId receive(Message message) {
        return receive(message, false);
    }
    public void submitted(ConversationId id, String requestId, String content) {
        String to = id.kind() == ConversationId.Kind.PRIVATE ? id.key() : null;
        Entry entry = new Entry(EntryKind.OUTGOING, self, to, content, Instant.now(), null, null, SendStatus.SENDING, null);
        outgoing.put(requestId, entry);
        conversations.get(id).entries.add(entry);
    }
    public ConversationId sendError(String requestId, boolean uncertain, String detail) {
        Entry entry = outgoing.get(requestId);
        if (entry == null || entry.sendStatus() == SendStatus.FAILED) return null;
        ConversationId id = entry.to() == null ? ConversationId.PUBLIC : ConversationId.privateWith(entry.to());
        List<Entry> entries = conversations.get(id).entries;
        Entry replacement = new Entry(EntryKind.OUTGOING, entry.from(), entry.to(), entry.content(), entry.time(),
                null, null, uncertain ? SendStatus.UNCONFIRMED : SendStatus.FAILED, detail);
        entries.set(entries.indexOf(entry), replacement);
        outgoing.put(requestId, replacement);
        return id;
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
        if (!historical && self.equals(message.from()) && message.messageId() != null) {
            Entry submitted = outgoing.get(message.requestId());
            if (submitted != null && Objects.equals(submitted.to(), message.to()) && (submitted.to() == null) == group
                    && submitted.content().equals(message.content())) {
                outgoing.remove(message.requestId());
                conversation.entries.remove(submitted);
                confirmedSends.add(message.messageId());
                // A history page may already have added the archive row while the echo was pending.
                if (messageIds.contains(message.messageId())) {
                    conversation.entries.removeIf(entry -> message.messageId().equals(entry.messageId()));
                    messageIds.remove(message.messageId());
                    updateSummary(conversation);
                }
            }
        }
        boolean firstLive = !historical && (message.messageId() == null || liveIds.add(message.messageId()));
        if (firstLive && !self.equals(message.from()) && !(visible && selected.equals(conversation.id))) conversation.unread++;
        if (message.messageId() != null && !messageIds.add(message.messageId())) return conversation.id;
        Instant time = parseTime(message.serverTime());
        Entry entry = new Entry(EntryKind.MESSAGE, message.from(), message.to(), message.content(), time, message.messageId(), message.nickname(),
                confirmedSends.contains(message.messageId()) ? SendStatus.CONFIRMED : null, null);
        int index = Collections.binarySearch(conversation.entries, entry, Comparator.comparing(Entry::time));
        if (index < 0) index = -index - 1;
        else while (index < conversation.entries.size() && !conversation.entries.get(index).time().isAfter(time)) index++;
        conversation.entries.add(index, entry);
        if (conversation.lastMessage == null || !time.isBefore(conversation.lastMessage.time())) conversation.lastMessage = entry;
        return conversation.id;
    }
    public void deleteHistory(String scope, String id) {
        ConversationId target = ConversationScope.PUBLIC.equals(scope) ? ConversationId.PUBLIC
                : ConversationId.privateWith(Arrays.stream(scope.split(":" )).skip(1).filter(p -> !p.equals(self)).findFirst().orElseThrow());
        Conversation conversation = conversations.get(target); if (conversation == null) return;
        conversation.entries.removeIf(entry -> {
            boolean remove = entry.kind() != EntryKind.OUTGOING && (id == null || id.equals(entry.messageId()));
            if (remove && entry.messageId() != null) {
                messageIds.remove(entry.messageId()); liveIds.remove(entry.messageId()); confirmedSends.remove(entry.messageId());
            }
            return remove;
        });
        conversation.unread = Math.min(conversation.unread, (int) conversation.entries.stream().filter(e -> e.kind() == EntryKind.MESSAGE && !self.equals(e.from())).count());
        updateSummary(conversation);
    }
    private void updateSummary(Conversation conversation) {
        Entry last = null;
        for (Entry entry : conversation.entries) if (entry.kind() == EntryKind.MESSAGE) last = entry;
        conversation.lastMessage = last;
        conversation.emptyPreview = "";
    }

    public void system(String text) {
        conversations.get(ConversationId.PUBLIC).entries.add(new Entry(EntryKind.SYSTEM, "", "", text, Instant.now()));
    }
    public ConversationId error(Message message) {
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
                .map(this::summary)
                .filter(c -> needle.isEmpty() || c.title().toLowerCase(Locale.ROOT).contains(needle)
                        || (c.id().kind() == ConversationId.Kind.PRIVATE && c.id().key().toLowerCase(Locale.ROOT).contains(needle))
                        || c.preview().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.<Summary>comparingInt(c -> c.id().equals(ConversationId.PUBLIC) ? 0 : 1)
                        .thenComparing(Summary::time, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Summary::title).thenComparing(c -> c.id().key()))
                .toList();
    }

    private static Instant parseTime(String value) {
        try { return Instant.parse(value); } catch (RuntimeException ex) { return Instant.now(); }
    }
}
