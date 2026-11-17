package cn.ltalk.server;

import cn.ltalk.common.*;
import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;

/** Persists relationships, requests and group membership together before acknowledging changes. */
final class SocialStore {
    record Range(String start, String end) { }
    private enum RequestKind { FRIEND, PRIVATE, JOIN, INVITE }
    private record Request(String id, RequestKind kind, String from, String to, String group) { }
    private record Group(String id, String title, String admin, boolean review, boolean closed,
                         Map<String, List<Range>> memberships) {
        boolean member(String user) {
            List<Range> ranges = memberships.get(user);
            return ranges != null && !ranges.isEmpty() && ranges.get(ranges.size() - 1).end() == null;
        }
        Group withSettings(String owner, boolean review, boolean closed) {
            return new Group(id, title, owner, review, closed, memberships);
        }
    }
    private record State(Set<String> friends, Set<String> privateAllowed, Set<String> blocked,
                         Map<String, Request> requests, Map<String, Group> groups) {
        State copy() {
            return new State(new HashSet<>(friends), new HashSet<>(privateAllowed), new HashSet<>(blocked),
                    new HashMap<>(requests), new HashMap<>(groups));
        }
    }

    private final Gson gson = new Gson();
    private final Path file;
    private final ChatArchive archive;
    private volatile State state;

    SocialStore(Path file, ChatArchive archive) throws IOException {
        this.file = file;
        this.archive = archive;
        if (!Files.exists(file)) {
            state = new State(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashMap<>(), new HashMap<>());
            return;
        }
        try {
            state = gson.fromJson(Files.readString(file), State.class);
            state.copy();
            for (var entry : state.groups.entrySet()) {
                Group group = entry.getValue();
                if (!ConversationScope.uuid(entry.getKey()).equals(group.id) || group.title == null || group.admin == null
                        || (!group.closed && !group.member(group.admin))) throw new IllegalArgumentException();
                for (List<Range> ranges : group.memberships.values()) {
                    if (ranges.isEmpty()) throw new IllegalArgumentException();
                    for (int i = 0; i < ranges.size(); i++) {
                        Range range = ranges.get(i);
                        Instant.parse(range.start);
                        if (range.end != null) Instant.parse(range.end);
                        else if (group.closed || i + 1 != ranges.size()) throw new IllegalArgumentException();
                    }
                }
            }
            for (Request request : state.requests.values()) {
                if (request.group != null && !state.groups.containsKey(request.group)) throw new IllegalArgumentException();
            }
        } catch (ProtocolException | RuntimeException ex) {
            throw new IOException("好友与群组数据损坏", ex);
        }
    }

    synchronized boolean friends(String a, String b) throws ProtocolException {
        return state.friends.contains(ConversationScope.privateChat(a, b));
    }

    synchronized boolean privateAllowed(String from, String to) throws ProtocolException {
        return friends(from, to) || (!blocked(state, from, to) && state.privateAllowed.contains(ConversationScope.privateChat(from, to)));
    }

    List<Range> ranges(String id, String user) {
        Group group = state.groups.get(id);
        return group == null ? List.of() : group.memberships.getOrDefault(user, List.of());
    }

    Set<String> groupScopes(String user) {
        Set<String> scopes = new HashSet<>();
        for (Group group : state.groups.values()) {
            if (group.memberships.containsKey(user)) scopes.add(ConversationScope.group(group.id));
        }
        return scopes;
    }

    synchronized Set<String> members(String id, String sender) throws ProtocolException {
        Group group = group(state, id);
        if (!group.member(sender)) throw error("FORBIDDEN", "只有群成员可以发送群消息");
        return activeMembers(group);
    }

    synchronized List<SocialItem> directory(String query, String user) throws ProtocolException {
        String key = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (key.length() > 64) throw error("BAD_MESSAGE", "群组搜索最多 64 个字符");
        return state.groups.values().stream()
                .filter(group -> !group.closed && (group.title.toLowerCase(Locale.ROOT).contains(key) || group.id.equals(key)))
                .map(group -> groupItem(group, user)).toList();
    }

    synchronized List<SocialItem> items(String user, AccountStore accounts) {
        List<SocialItem> rows = new ArrayList<>();
        addPeers(rows, state.friends, user, SocialItem.Kind.FRIEND, accounts);
        addPeers(rows, state.privateAllowed, user, SocialItem.Kind.PRIVATE_ALLOWED, accounts);
        for (String key : state.blocked) {
            if (key.startsWith(user + ":")) {
                String target = key.substring(user.length() + 1);
                rows.add(SocialItem.user(SocialItem.Kind.PRIVATE_BLOCKED, target, accounts.nickname(target)));
            }
        }
        for (Group group : state.groups.values()) {
            if (group.memberships.containsKey(user)) rows.add(groupItem(group, user));
        }
        for (Request request : state.requests.values()) {
            SocialItem row = requestItem(request, user, accounts);
            if (row != null) rows.add(row);
        }
        return rows;
    }

    private SocialItem requestItem(Request request, String user, AccountStore accounts) {
        boolean sent = request.from.equals(user);
        Group group = state.groups.get(request.group);
        boolean received = request.kind == RequestKind.JOIN ? group.admin.equals(user) : user.equals(request.to);
        if (!received && (!sent || request.kind == RequestKind.INVITE)) return null;
        SocialItem.Kind kind = switch (request.kind) {
            case FRIEND -> sent ? SocialItem.Kind.FRIEND_OUT : SocialItem.Kind.FRIEND_IN;
            case PRIVATE -> sent ? SocialItem.Kind.PRIVATE_OUT : SocialItem.Kind.PRIVATE_IN;
            case JOIN -> sent ? SocialItem.Kind.JOIN_OUT : SocialItem.Kind.JOIN_IN;
            case INVITE -> SocialItem.Kind.INVITE_IN;
        };
        String peer = sent ? request.to : request.from;
        return new SocialItem(kind, request.id, peer, request.group,
                group == null ? accounts.nickname(peer) : group.title,
                group == null ? null : group.admin, group == null ? null : group.review, null);
    }

    private static void addPeers(List<SocialItem> rows, Set<String> pairs, String user, SocialItem.Kind kind, AccountStore accounts) {
        for (String pair : pairs) {
            String[] ids = pair.split(":");
            String peer = user.equals(ids[1]) ? ids[2] : user.equals(ids[2]) ? ids[1] : null;
            if (peer != null) rows.add(SocialItem.user(kind, peer, accounts.nickname(peer)));
        }
    }

    private static SocialItem groupItem(Group group, String user) {
        return new SocialItem(SocialItem.Kind.GROUP, group.id, null, group.id, group.title,
                group.closed ? null : group.admin, group.review, group.member(user));
    }

    synchronized Set<String> change(String user, SocialAction action, Message message, AccountStore accounts,
                                    Predicate<String> online) throws IOException {
        State next = state.copy();
        Set<String> affected = new HashSet<>();
        affected.add(user);
        switch (action) {
            case FRIEND_REQUEST, PRIVATE_REQUEST, FRIEND_REMOVE, PRIVATE_BLOCK, PRIVATE_UNBLOCK ->
                    changePair(next, user, action, message.to(), accounts, online, affected);
            case FRIEND_ACCEPT, FRIEND_REJECT, PRIVATE_ACCEPT, PRIVATE_REJECT ->
                    decidePair(next, user, action, message.messageId(), affected);
            case GROUP_CREATE, GROUP_REQUEST, GROUP_APPROVE, GROUP_REJECT, GROUP_INVITE, INVITE_ACCEPT,
                    INVITE_REJECT, GROUP_REVIEW, GROUP_TRANSFER, GROUP_LEAVE, GROUP_DISBAND ->
                    changeGroup(next, user, action, message, accounts, affected);
            default -> throw error("BAD_MESSAGE", "无效的操作");
        }
        Path temp = Files.createTempFile(file.getParent(), ".social-", ".tmp");
        try {
            Files.writeString(temp, gson.toJson(next));
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            state = next;
        } finally { Files.deleteIfExists(temp); }
        return affected;
    }

    private void changePair(State next, String user, SocialAction action, String target, AccountStore accounts,
                            Predicate<String> online, Set<String> affected) throws IOException {
        String peer = existingUser(accounts, target);
        String pair = ConversationScope.privateChat(user, peer);
        affected.add(peer);
        switch (action) {
            case FRIEND_REQUEST -> {
                if (next.friends.contains(pair)) throw error("ALREADY_EXISTS", "双方已经是好友");
                requestPair(next, RequestKind.FRIEND, user, peer);
            }
            case PRIVATE_REQUEST -> {
                if (!online.test(peer)) throw error("USER_OFFLINE", "对方离线，暂不能申请私聊");
                if (blocked(next, user, peer)) throw error("PRIVATE_FORBIDDEN", "无法向此用户申请私聊");
                if (next.friends.contains(pair) || next.privateAllowed.contains(pair))
                    throw error("ALREADY_EXISTS", "双方已经可以私聊");
                requestPair(next, RequestKind.PRIVATE, user, peer);
            }
            case FRIEND_REMOVE -> {
                next.friends.remove(pair);
                next.requests.values().removeIf(req -> req.kind == RequestKind.INVITE && samePair(req, user, peer));
            }
            case PRIVATE_BLOCK -> {
                if (next.friends.contains(pair)) throw error("BAD_MESSAGE", "请先解除好友关系");
                next.blocked.add(user + ":" + peer);
                next.privateAllowed.remove(pair);
                next.requests.values().removeIf(req -> req.kind == RequestKind.PRIVATE && samePair(req, user, peer));
            }
            case PRIVATE_UNBLOCK -> next.blocked.remove(user + ":" + peer);
            default -> throw new IllegalArgumentException(action.name());
        }
    }

    private void decidePair(State next, String user, SocialAction action, String id, Set<String> affected) throws IOException {
        boolean friendship = action == SocialAction.FRIEND_ACCEPT || action == SocialAction.FRIEND_REJECT;
        Request pending = pending(next, id, friendship ? RequestKind.FRIEND : RequestKind.PRIVATE);
        if (!user.equals(pending.to)) throw error("FORBIDDEN", "只有接收方可以处理此请求");
        affected.add(pending.from);
        next.requests.remove(id);
        String pair = ConversationScope.privateChat(pending.from, user);
        if (action == SocialAction.FRIEND_ACCEPT) {
            next.friends.add(pair);
            next.blocked.remove(user + ":" + pending.from);
            next.blocked.remove(pending.from + ":" + user);
            next.requests.values().removeIf(req -> req.kind == RequestKind.PRIVATE && samePair(req, user, pending.from));
        } else if (action == SocialAction.PRIVATE_ACCEPT) {
            if (blocked(next, user, pending.from)) throw error("PRIVATE_FORBIDDEN", "无法建立私聊");
            next.privateAllowed.add(pair);
        }
    }

    private static boolean blocked(State state, String a, String b) {
        return state.blocked.contains(a + ":" + b) || state.blocked.contains(b + ":" + a);
    }

    private static boolean samePair(Request req, String a, String b) {
        return (a.equals(req.from) && b.equals(req.to)) || (b.equals(req.from) && a.equals(req.to));
    }

    private static void requestPair(State state, RequestKind kind, String from, String to) throws ProtocolException {
        if (state.requests.values().stream().anyMatch(req -> req.kind == kind && samePair(req, from, to)))
            throw error("REQUEST_PENDING", "双方已有待处理请求");
        addRequest(state, kind, from, to, null);
    }

    private static void addRequest(State state, RequestKind kind, String from, String to, String group) {
        String id = UUID.randomUUID().toString();
        state.requests.put(id, new Request(id, kind, from, to, group));
    }

    private static Request pending(State state, String id, RequestKind kind) throws ProtocolException {
        Request req = state.requests.get(ConversationScope.uuid(id));
        if (req == null || req.kind != kind) throw error("NOT_FOUND", "请求已处理或不存在");
        return req;
    }

    static String existingUser(AccountStore accounts, String input) throws ProtocolException {
        String user = Validation.userId(input);
        if (accounts.nickname(user) == null) throw error("NOT_FOUND", "找不到该用户名");
        return user;
    }

    private void changeGroup(State next, String user, SocialAction action, Message message, AccountStore accounts,
                             Set<String> affected) throws IOException {
        if (action == SocialAction.GROUP_CREATE) {
            String title = message.nickname() == null ? "" : message.nickname().strip();
            if (title.isEmpty() || title.length() > 40 || title.codePoints().anyMatch(Character::isISOControl))
                throw error("BAD_MESSAGE", "群名须为 1～40 个字符");
            String id = UUID.randomUUID().toString();
            Group group = new Group(id, title, user, review(message), false, new HashMap<>());
            join(next, group, user, affected);
            return;
        }
        Request request = switch (action) {
            case GROUP_APPROVE, GROUP_REJECT -> pending(next, message.messageId(), RequestKind.JOIN);
            case INVITE_ACCEPT, INVITE_REJECT -> pending(next, message.messageId(), RequestKind.INVITE);
            default -> null;
        };
        String id = request == null ? ConversationScope.groupId(message.conversationId()) : request.group;
        Group group = group(next, id);
        switch (action) {
            case GROUP_REQUEST -> applyForGroup(next, group, user, affected);
            case GROUP_APPROVE, GROUP_REJECT -> {
                requireAdmin(group, user);
                next.requests.remove(request.id);
                affected.add(request.from);
                if (action == SocialAction.GROUP_APPROVE) join(next, group, request.from, affected);
            }
            case GROUP_INVITE -> {
                if (!group.member(user)) throw error("FORBIDDEN", "只有群成员可以发出邀请");
                String friend = existingUser(accounts, message.to());
                if (!next.friends.contains(ConversationScope.privateChat(user, friend)))
                    throw error("FORBIDDEN", "只能邀请自己的好友");
                if (group.member(friend)) throw error("ALREADY_EXISTS", "对方已经是该群的成员");
                if (next.requests.values().stream().anyMatch(req -> req.kind == RequestKind.INVITE && id.equals(req.group)
                        && user.equals(req.from) && friend.equals(req.to)))
                    throw error("REQUEST_PENDING", "你已经发送过该邀请");
                addRequest(next, RequestKind.INVITE, user, friend, id);
                affected.add(friend);
            }
            case INVITE_ACCEPT, INVITE_REJECT -> {
                if (!user.equals(request.to)) throw error("FORBIDDEN", "只能处理发给自己的邀请");
                next.requests.remove(request.id);
                affected.add(request.from);
                if (action == SocialAction.INVITE_ACCEPT) {
                    if (!group.member(request.from) || !next.friends.contains(ConversationScope.privateChat(user, request.from)))
                        throw error("FORBIDDEN", "此邀请已失效");
                    if (group.admin.equals(request.from)) join(next, group, user, affected);
                    else if (!hasJoin(next, group, user) || !group.review) applyForGroup(next, group, user, affected);
                }
            }
            case GROUP_REVIEW -> {
                requireAdmin(group, user);
                next.groups.put(id, group.withSettings(user, review(message), false));
                affected.addAll(activeMembers(group));
            }
            case GROUP_TRANSFER -> {
                requireAdmin(group, user);
                String owner = existingUser(accounts, message.to());
                if (owner.equals(user) || !group.member(owner)) throw error("BAD_MESSAGE", "请选择另一位现有群成员");
                next.groups.put(id, group.withSettings(owner, group.review, false));
                affected.addAll(activeMembers(group));
            }
            case GROUP_LEAVE -> {
                if (!group.member(user)) throw error("FORBIDDEN", "当前账号已不在该群");
                if (group.admin.equals(user)) throw error("ADMIN_REQUIRED", "请先转让管理员，或选择解散群组");
                next.groups.put(id, endMembership(group, user, archive.lastTime()));
                cancelGroupRequests(next, id, req -> req.kind == RequestKind.INVITE && user.equals(req.from), affected);
            }
            case GROUP_DISBAND -> {
                requireAdmin(group, user);
                Group archived = group.withSettings(user, group.review, true);
                for (String member : activeMembers(group)) archived = endMembership(archived, member, archive.lastTime());
                next.groups.put(id, archived);
                affected.addAll(group.memberships.keySet());
                cancelGroupRequests(next, id, req -> true, affected);
            }
            default -> throw new IllegalArgumentException(action.name());
        }
    }

    private void applyForGroup(State next, Group group, String user, Set<String> affected) throws IOException {
        if (group.member(user)) throw error("ALREADY_EXISTS", "你已在该群");
        if (group.review) {
            if (hasJoin(next, group, user)) throw error("REQUEST_PENDING", "入群申请正在审核");
            addRequest(next, RequestKind.JOIN, user, null, group.id);
            affected.add(group.admin);
        } else join(next, group, user, affected);
    }

    private static boolean hasJoin(State state, Group group, String user) {
        return state.requests.values().stream().anyMatch(req -> req.kind == RequestKind.JOIN && group.id.equals(req.group)
                && user.equals(req.from));
    }

    private void join(State next, Group group, String user, Set<String> affected) throws IOException {
        if (group.member(user)) throw error("ALREADY_EXISTS", "该账号已在群内");
        Map<String, List<Range>> memberships = new HashMap<>(group.memberships);
        List<Range> ranges = new ArrayList<>(memberships.getOrDefault(user, List.of()));
        ranges.add(new Range(archive.groupHistoryStart(group.id), null));
        memberships.put(user, List.copyOf(ranges));
        next.groups.put(group.id, new Group(group.id, group.title, group.admin, group.review, group.closed, memberships));
        affected.add(user);
        affected.add(group.admin);
        cancelGroupRequests(next, group.id,
                req -> (req.kind == RequestKind.JOIN && user.equals(req.from)) || (req.kind == RequestKind.INVITE && user.equals(req.to)),
                affected);
    }

    private static Group endMembership(Group group, String user, String end) {
        Map<String, List<Range>> memberships = new HashMap<>(group.memberships);
        List<Range> ranges = new ArrayList<>(memberships.get(user));
        Range open = ranges.remove(ranges.size() - 1);
        ranges.add(new Range(open.start, end));
        memberships.put(user, List.copyOf(ranges));
        return new Group(group.id, group.title, group.admin, group.review, group.closed, memberships);
    }

    private static void cancelGroupRequests(State state, String group, Predicate<Request> filter, Set<String> affected) {
        state.requests.values().removeIf(req -> {
            if (!group.equals(req.group) || !filter.test(req)) return false;
            affected.add(req.from);
            if (req.to != null) affected.add(req.to);
            return true;
        });
    }

    private static Set<String> activeMembers(Group group) {
        Set<String> result = new HashSet<>();
        for (String user : group.memberships.keySet()) if (group.member(user)) result.add(user);
        return result;
    }

    private static Group group(State state, String id) throws ProtocolException {
        Group group = state.groups.get(id);
        if (group == null || group.closed) throw error("NOT_FOUND", "群组不存在或已解散");
        return group;
    }

    private static void requireAdmin(Group group, String user) throws ProtocolException {
        if (!group.admin.equals(user)) throw error("FORBIDDEN", "只有群管理员可以执行此操作");
    }

    private static boolean review(Message message) throws ProtocolException {
        if ("REVIEW".equals(message.content())) return true;
        if ("OPEN".equals(message.content())) return false;
        throw error("BAD_MESSAGE", "请选择入群审核方式");
    }

    private static ProtocolException error(String code, String text) { return new ProtocolException(code, text); }
}
