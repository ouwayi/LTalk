package cn.ltalk.client.ui;

import cn.ltalk.client.ContactProfile;
import cn.ltalk.common.Message;
import cn.ltalk.common.MessageType;
import cn.ltalk.common.ChatRecord;
import cn.ltalk.common.ConversationScope;
import cn.ltalk.common.SocialItem;
import cn.ltalk.common.Attachment;

import java.time.Instant;
import java.util.*;

/** EDT-confined presentation state; no sockets, protocol changes, persistence or delivery claims. */
public final class ConversationStore {
    public enum EntryKind { MESSAGE, OUTGOING, SYSTEM, ERROR }
    public enum SendStatus { SENDING, CONFIRMED, FAILED, UNCONFIRMED }
    public record Entry(EntryKind kind, String from, String to, String content, Instant time, String messageId,
                        String nickname, SendStatus sendStatus, String sendDetail, String replyTo, Attachment attachment) {
        public Entry(EntryKind kind, String from, String to, String content, Instant time) { this(kind, from, to, content, time, null); }
        public Entry(EntryKind kind, String from, String to, String content, Instant time, String messageId) {
            this(kind, from, to, content, time, messageId, null);
        }
        public Entry(EntryKind kind, String from, String to, String content, Instant time, String messageId, String nickname) {
            this(kind, from, to, content, time, messageId, nickname, null, null, null, null);
        }
        public String displayName() {
            return ContactProfile.displayName(from, nickname);
        }
    }
    public record Summary(ConversationId id, String title, String preview, Instant time, int unread, boolean online,
                          ContactProfile profile) { }
    public record Unread(Entry first, Entry last) { }

    private static final class Conversation {
        final ConversationId id;
        final List<Entry> entries = new ArrayList<>();
        String draft = "";
        String replyDraft;
        boolean online;
        final List<Entry> unread = new ArrayList<>();
        Entry lastMessage;
        String emptyPreview = "";
        String label;
        Conversation(ConversationId id) { this.id = id; }
    }

    private final Map<ConversationId, Conversation> conversations = new LinkedHashMap<>();
    private final Map<String, ContactProfile> profiles = new HashMap<>();
    private final Set<String> messageIds = new HashSet<>();
    private final Set<String> liveIds = new HashSet<>();
    private record Outgoing(ConversationId id, Entry entry) { }
    private final Map<String, Outgoing> outgoing = new HashMap<>();
    private final Set<String> confirmedSends = new HashSet<>();
    private final Set<String> friends = new HashSet<>(), allowed = new HashSet<>(), blocked = new HashSet<>(), pending = new HashSet<>();
    private final Map<String, SocialItem> groups = new HashMap<>();
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
        friends.clear(); allowed.clear(); blocked.clear(); pending.clear(); groups.clear();
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
    public List<String> onlineUsers() { return onlineUsers; }
    public String draft() { return conversations.get(selected).draft; }
    public void saveDraft(String text) { conversations.get(selected).draft = text; }
    public String replyDraft() { return conversations.get(selected).replyDraft; }
    public void replyDraft(String id) { conversations.get(selected).replyDraft = id; }
    public Entry message(String id) {
        if (id == null) return null;
        for (Entry entry : conversations.get(selected).entries) if (id.equals(entry.messageId())) return entry;
        return null;
    }

    public Unread unread() {
        List<Entry> unread = conversations.get(selected).unread;
        return unread.isEmpty() ? null : new Unread(unread.get(0), unread.get(unread.size() - 1));
    }

    public boolean markRead(Entry through) {
        List<Entry> unread = conversations.get(selected).unread;
        for (int i = 0; i < unread.size(); i++) {
            if (unread.get(i) != through) continue;
            unread.subList(0, i + 1).clear();
            return true;
        }
        return false;
    }
    public boolean friend(String user) { return friends.contains(user); }
    public boolean blocked(String user) { return blocked.contains(user); }
    public boolean privatePending(String user) { return pending.contains(user); }
    public boolean online(String user) { return onlineUsers.contains(user); }
    public boolean mayRequestPrivate(String user) { return online(user) && !friend(user) && !allowed.contains(user)
            && !blocked(user) && !pending.contains(user); }
    public SocialItem groupInfo(String id) { return groups.get(id); }
    public Summary privateSummary(String user) { return summary(ensurePrivate(user)); }
    public ConversationId openPrivate(String user) { return ensurePrivate(user).id; }
    public ConversationId openGroup(String id) { return ensureGroup(id).id; }

    public Set<ConversationId> updateSocial(List<SocialItem> items) {
        Map<String, SocialItem> previousGroups = new HashMap<>(groups);
        friends.clear(); allowed.clear(); blocked.clear(); pending.clear(); groups.clear();
        for (SocialItem row : items) {
            switch (row.kind()) {
                case FRIEND -> {
                    friends.add(row.user());
                    ensurePrivate(row.user()).label = row.title();
                }
                case PRIVATE_ALLOWED -> {
                    allowed.add(row.user());
                    Conversation pair = conversations.get(ConversationId.privateWith(row.user()));
                    if (pair != null) pair.label = row.title();
                }
                case PRIVATE_BLOCKED -> blocked.add(row.user());
                case PRIVATE_IN, PRIVATE_OUT -> pending.add(row.user());
                case GROUP -> {
                    groups.put(row.group(), row);
                    ensureGroup(row.group()).label = row.title();
                }
                default -> { }
            }
        }
        Set<ConversationId> joined = new HashSet<>();
        for (SocialItem row : groups.values()) {
            SocialItem previous = previousGroups.get(row.group());
            if (Boolean.TRUE.equals(row.member()) && (previous == null || !Boolean.TRUE.equals(previous.member())))
                joined.add(ConversationId.group(row.group()));
        }
        return joined;
    }

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
        if (preview.isEmpty() && !group && friend(key)) preview = conversation.online ? "好友 · 在线" : "好友 · 离线";
        if (preview.isEmpty() && group && !conversation.id.equals(ConversationId.PUBLIC)) {
            SocialItem info = groups.get(key);
            if (info != null) preview = joined(key) ? "点击打开群聊" : info.admin() == null ? "已解散 · 查看旧记录" : "已退群 · 查看旧记录";
        }
        if (last != null) {
            String prefix = group ? displayName(last.from(), last.displayName()) + "："
                    : self.equals(last.from()) ? "你：" : "";
            preview = prefix + last.content();
        }
        String label = Objects.requireNonNullElse(conversation.label, key);
        String title = conversation.id.equals(ConversationId.PUBLIC) ? "公共聊天室" : group ? label : displayName(key, label);
        return new Summary(conversation.id, title, preview, last == null ? null : last.time(), conversation.unread.size(),
                group && !conversation.id.equals(ConversationId.PUBLIC) ? joined(key) : conversation.online, group ? null : profiles.get(key));
    }

    public void select(ConversationId id) {
        Conversation conversation = conversations.get(id);
        if (conversation == null) throw new IllegalArgumentException("会话不存在");
        selected = id;
        selectionConfirmed = id.kind() == ConversationId.Kind.GROUP || conversation.online;
    }

    public void setVisible(boolean value) {
        visible = value;
    }

    public boolean canSend() {
        Conversation current = conversations.get(selected);
        if (selected.equals(ConversationId.PUBLIC)) return true;
        if (selected.kind() == ConversationId.Kind.GROUP) return joined(selected.key());
        return friend(selected.key()) || (selectionConfirmed && current.online && allowed.contains(selected.key()));
    }

    public boolean joined(String id) {
        SocialItem group = groups.get(id);
        return group != null && Boolean.TRUE.equals(group.member());
    }

    public String targetLabel() {
        if (selected.equals(ConversationId.PUBLIC)) return "群聊 · 所有在线用户";
        if (selected.kind() == ConversationId.Kind.GROUP) {
            SocialItem group = groups.get(selected.key());
            if (group != null && group.admin() == null) return "群组已解散 · 可查看旧记录";
            if (!joined(selected.key())) return "已退群 · 可查看旧记录";
            return self.equals(group.admin()) ? "群组 · 你是管理员" : "群组 · 仅成员可见";
        }
        String prefix = "私聊 " + displayName(selected.key(), selected.key());
        if (friend(selected.key())) return prefix + (online(selected.key()) ? " · 好友在线" : " · 好友离线，可以留言");
        if (blocked(selected.key())) return prefix + " · 已屏蔽";
        if (pending.contains(selected.key())) return prefix + " · 等待处理私聊请求";
        if (!online(selected.key())) return prefix + (allowed.contains(selected.key()) ? " · 对象已离线，请重新选择会话" : " · 对方离线");
        if (!allowed.contains(selected.key())) return prefix + " · 需要私聊许可";
        if (!selectionConfirmed && conversations.get(selected).online) return prefix + " · 已上线，请重新选择会话";
        if (!canSend()) return prefix + " · 对象已离线，请重新选择会话";
        return prefix + " · 在线，仅双方可见";
    }

    public boolean updateUsers(List<String> users) {
        onlineUsers = List.copyOf(users);
        Conversation current = conversations.get(selected);
        boolean invalidated = selected.kind() == ConversationId.Kind.PRIVATE && !friend(selected.key()) && selectionConfirmed
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
    private Conversation ensureGroup(String group) {
        ConversationId id = ConversationId.group(group);
        return conversations.computeIfAbsent(id, Conversation::new);
    }
    private Conversation groupConversation(String scope) {
        return ConversationScope.isGroup(scope) ? ensureGroup(scope.substring("GROUP:".length())) : conversations.get(ConversationId.PUBLIC);
    }

    public ConversationId receive(Message message) {
        return receive(message, true);
    }
    public ConversationId receive(Message message, boolean followingLatest) {
        return receive(message, false, followingLatest);
    }
    public void submitted(ConversationId id, String requestId, String content) {
        submitted(id, requestId, content, null);
    }
    public void submitted(ConversationId id, String requestId, String content, String replyTo) {
        String to = id.kind() == ConversationId.Kind.PRIVATE ? id.key() : null;
        Entry entry = new Entry(EntryKind.OUTGOING, self, to, content, Instant.now(), null, null, SendStatus.SENDING, null, replyTo, null);
        outgoing.put(requestId, new Outgoing(id, entry));
        conversations.get(id).entries.add(entry);
    }
    public ConversationId sendError(String requestId, boolean uncertain, String detail) {
        Outgoing pending = outgoing.get(requestId);
        if (pending == null || pending.entry.sendStatus() == SendStatus.FAILED) return null;
        Entry entry = pending.entry;
        ConversationId id = pending.id;
        List<Entry> entries = conversations.get(id).entries;
        Entry replacement = new Entry(EntryKind.OUTGOING, entry.from(), entry.to(), entry.content(), entry.time(),
                null, null, uncertain ? SendStatus.UNCONFIRMED : SendStatus.FAILED, detail, entry.replyTo(), null);
        entries.set(entries.indexOf(entry), replacement);
        outgoing.put(requestId, new Outgoing(id, replacement));
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
                .conversationId(record.conversationId()).to(record.recipient()).content(record.content())
                .serverTime(record.sentAt()).nickname(nickname).replyTo(record.replyTo()).attachment(record.attachment()).build(), true, true);
    }
    public void summarize(ChatRecord record, String nickname) {
        Conversation conversation = "GROUP_MESSAGE".equals(record.messageType()) ? groupConversation(record.conversationId())
                : ensurePrivate(self.equals(record.sender()) ? record.recipient() : record.sender());
        updateLatest(conversation, archivedEntry(record, nickname));
    }
    public static Entry archivedEntry(ChatRecord record, String nickname) {
        return new Entry(EntryKind.MESSAGE, record.sender(), record.recipient(), record.content(),
                parseTime(record.sentAt()), record.messageId(), nickname, null, null, record.replyTo(), record.attachment());
    }
    private ConversationId receive(Message message, boolean historical, boolean followingLatest) {
        boolean group = message.type() == MessageType.GROUP_MESSAGE;
        String peer = self.equals(message.from()) ? message.to() : message.from();
        Conversation conversation = group ? groupConversation(message.conversationId()) : ensurePrivate(peer);
        if (!historical && self.equals(message.from()) && message.messageId() != null) {
            Outgoing submitted = outgoing.get(message.requestId());
            if (submitted != null && submitted.id.equals(conversation.id) && submitted.entry.content().equals(message.content())
                    && Objects.equals(submitted.entry.replyTo(), message.replyTo())) {
                outgoing.remove(message.requestId());
                conversation.entries.remove(submitted.entry);
                confirmedSends.add(message.messageId());
                // A history page may already have added the archive row while the echo was pending.
                if (messageIds.contains(message.messageId())) {
                    conversation.entries.removeIf(entry -> message.messageId().equals(entry.messageId()));
                    messageIds.remove(message.messageId());
                }
            }
        }
        boolean firstLive = !historical && (message.messageId() == null || liveIds.add(message.messageId()));
        Instant time = parseTime(message.serverTime());
        Entry entry = new Entry(EntryKind.MESSAGE, message.from(), message.to(), message.content(), time, message.messageId(), message.nickname(),
                confirmedSends.contains(message.messageId()) ? SendStatus.CONFIRMED : null, null, message.replyTo(), message.attachment());
        if (firstLive && !self.equals(message.from()) && !(visible && selected.equals(conversation.id) && followingLatest))
            conversation.unread.add(entry);
        if (message.messageId() != null && !messageIds.add(message.messageId())) return conversation.id;
        int index = Collections.binarySearch(conversation.entries, entry, Comparator.comparing(Entry::time));
        if (index < 0) index = -index - 1;
        else while (index < conversation.entries.size() && !conversation.entries.get(index).time().isAfter(time)) index++;
        conversation.entries.add(index, entry);
        updateLatest(conversation, entry);
        return conversation.id;
    }
    private static void updateLatest(Conversation conversation, Entry entry) {
        if (conversation.lastMessage == null || !entry.time().isBefore(conversation.lastMessage.time()))
            conversation.lastMessage = entry;
    }
    public void deleteHistory(String scope, String id) {
        ConversationId target = ConversationScope.PUBLIC.equals(scope) ? ConversationId.PUBLIC
                : ConversationScope.isGroup(scope) ? groupConversation(scope).id
                : ConversationId.privateWith(Arrays.stream(scope.split(":" )).skip(1).filter(p -> !p.equals(self)).findFirst().orElseThrow());
        Conversation conversation = conversations.get(target); if (conversation == null) return;
        conversation.entries.removeIf(entry -> {
            boolean remove = entry.kind() != EntryKind.OUTGOING && (id == null || id.equals(entry.messageId()));
            if (remove && entry.messageId() != null) {
                messageIds.remove(entry.messageId()); liveIds.remove(entry.messageId()); confirmedSends.remove(entry.messageId());
            }
            return remove;
        });
        conversation.unread.removeIf(entry -> id == null || id.equals(entry.messageId()));
        if (id == null || id.equals(conversation.replyDraft)) conversation.replyDraft = null;
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

    public boolean inPublicList(Summary row) {
        return row.id().equals(ConversationId.PUBLIC)
                || (row.id().kind() == ConversationId.Kind.PRIVATE && online(row.id().key()));
    }

    public boolean inContactsList(Summary row) {
        ConversationId id = row.id();
        return id.kind() == ConversationId.Kind.PRIVATE ? friend(id.key()) : !id.equals(ConversationId.PUBLIC) && joined(id.key());
    }

    public boolean hasDraft(ConversationId id) {
        Conversation conversation = conversations.get(id);
        return conversation != null && (!conversation.draft.isEmpty() || conversation.replyDraft != null);
    }

    private static Instant parseTime(String value) {
        try { return Instant.parse(value); } catch (RuntimeException ex) { return Instant.now(); }
    }
}
