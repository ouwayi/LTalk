package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

final class MessageRouter {
    private static final Logger LOG = Logger.getLogger(MessageRouter.class.getName());
    private final ChatServer server;
    private final SessionRegistry registry;
    private final Object mutationOrder = new Object();

    MessageRouter(ChatServer server, SessionRegistry registry) { this.server = server; this.registry = registry; }

    void handle(ClientSession session, Message request) {
        if (request.type() == MessageType.GROUP_SEND || request.type() == MessageType.PRIVATE_SEND
                || request.type() == MessageType.HISTORY_DELETE || request.type() == MessageType.SOCIAL_REQUEST) {
            // A deleted record's earlier live delivery must not overtake the deletion acknowledgement.
            synchronized (mutationOrder) { handleRequest(session, request); }
        } else handleRequest(session, request);
    }
    private void handleRequest(ClientSession session, Message request) {
        try {
            if (request.type() == MessageType.HELLO) {
                Validation.requestId(request.requestId());
                if (session.greeted || session.userId != null) throw new ProtocolException("BAD_MESSAGE", "不能重复建立连接");
                session.greeted = true;
                server.greeted(session);
                server.deliver(session, Message.of(MessageType.HELLO_OK).requestId(request.requestId()).build());
                session.heartbeat.start();
                return;
            }
            if (session.greeted && (request.type() == MessageType.PING || request.type() == MessageType.PONG)) {
                session.heartbeat.receive(request); return;
            }
            if (request.type() == MessageType.REGISTER) {
                if (session.ready) throw new ProtocolException("BAD_MESSAGE", "请先退出当前登录");
                Validation.requestId(request.requestId());
                if (request.usernameMode() != null && !List.of("MANUAL", "RANDOM").contains(request.usernameMode()))
                    throw new ProtocolException("BAD_MESSAGE", "用户名来源须为 MANUAL 或 RANDOM");
                String id = server.authenticate(session, request, () -> server.accounts().register(
                        request.userId(), request.nickname(), request.password(), "RANDOM".equals(request.usernameMode())));
                server.deliver(session, Message.of(MessageType.REGISTER_OK).requestId(request.requestId()).userId(id).build());
                return;
            }
            if (request.type() == MessageType.LOGIN) {
                if (session.userId != null)
                    throw new ProtocolException("ALREADY_LOGGED_IN", "当前连接不能重复登录");
                login(session, request);
                return;
            }
            if (!session.ready) throw new ProtocolException("NOT_AUTHENTICATED", "请先登录");
            switch (request.type()) {
                case PING, PONG -> session.heartbeat.receive(request);
                case LOGOUT -> session.close("用户主动退出");
                case EXPORT_REQUEST -> export(session, request);
                case HISTORY_REQUEST -> history(session, request);
                case HISTORY_VIEW_REQUEST -> historyView(session, request);
                case SOCIAL_REQUEST -> social(session, request);
                case HISTORY_DELETE -> {
                    Validation.requestId(request.requestId()); ownArchive(session, request);
                    server.archive().delete(session.userId, request.conversationId(), request.messageId());
                    session.closeHistory(); session.closeExport();
                    server.deliver(session, Message.of(MessageType.HISTORY_DELETED).requestId(request.requestId())
                            .conversationId(request.conversationId()).messageId(request.messageId()).build());
                }
                case PROFILE_GET, PROFILE_UPDATE -> {
                    Validation.requestId(request.requestId());
                    if (request.userId() != null && !session.userId.equals(request.userId()))
                        throw new ProtocolException("FORBIDDEN", "只能查看和修改本人的资料");
                    UserProfile profile = request.type() == MessageType.PROFILE_GET ? server.accounts().profile(session.userId)
                            : server.accounts().updateProfile(session.userId, request.nickname(), request.avatar());
                    server.deliver(session, profileMessage(MessageType.PROFILE_OK, request.requestId(), profile));
                    if (request.type() == MessageType.PROFILE_UPDATE) {
                        Message display = displayMessage(session.userId);
                        if (display != null) server.broadcast(display);
                    }
                }
                case PASSWORD_CHANGE -> {
                    Validation.requestId(request.requestId());
                    if (request.userId() != null && !session.userId.equals(request.userId())) {
                        throw new ProtocolException("FORBIDDEN", "只能修改本人的密码");
                    }
                    server.authenticate(session, request, () -> {
                        server.accounts().changePassword(session.userId, request.password(), request.newPassword());
                        return null;
                    });
                    server.deliver(session, Message.of(MessageType.PASSWORD_CHANGED).requestId(request.requestId()).build());
                }
                case GROUP_SEND -> {
                    validateChat(request);
                    publishChat(session, request, routeChat(session, request));
                }
                case PRIVATE_SEND -> {
                    validateChat(request);
                    publishChat(session, request, routeChat(session, request));
                }
                case ATTACH_UPLOAD, ATTACH_DOWNLOAD -> server.attachments().prepare(session, request);
                case ATTACH_CANCEL -> {
                    Validation.requestId(request.requestId());
                    server.attachments().cancel(session, request.requestId());
                }
                default -> throw new ProtocolException("BAD_MESSAGE", "此消息类型不能由客户端发送");
            }
        } catch (ProtocolException ex) {
            LOG.fine(() -> "请求失败 type=" + request.type() + " code=" + ex.code());
            String requestId = request.requestId();
            if (requestId != null && (requestId.isBlank() || requestId.length() > 64)) requestId = null;
            Message error = Message.error(requestId, ex.code(), ex.getMessage());
            if (request.type() == MessageType.LOGIN && session.userId == null && !session.greeted) {
                server.deliverAndWait(session, error);
                session.close("登录失败：" + ex.code());
            } else server.deliver(session, error);
        } catch (IOException ex) {
            if (request.type() == MessageType.EXPORT_REQUEST) session.closeExport();
            if (request.type() == MessageType.HISTORY_REQUEST) session.closeHistory();
            server.deliver(session, Message.error(request.requestId(), request.type() == MessageType.EXPORT_REQUEST ? "EXPORT_FAILED" : "STORAGE_ERROR",
                    "服务端文件操作失败，请稍后重试"));
        }
    }

    private record ChatRoute(List<ClientSession> group, ClientSession target) { }

    private ChatRoute routeChat(ClientSession session, Message request) throws IOException {
        if (request.type() == MessageType.GROUP_SEND) {
            String scope = request.conversationId() == null ? ConversationScope.PUBLIC : request.conversationId();
            Set<String> members = ConversationScope.PUBLIC.equals(scope) ? null
                    : server.social().members(ConversationScope.groupId(scope), session.userId);
            server.archive().requireVisibleReply(session.userId, scope, request.replyTo());
            return new ChatRoute(registry.readySessions().stream()
                    .filter(peer -> members == null || members.contains(peer.userId)).toList(), null);
        }
        if (request.to() == null || !request.to().matches("[A-Za-z0-9_]{1,20}"))
            throw new ProtocolException("BAD_MESSAGE", "私聊需要合法的 to 用户标识");
        if (request.to().equals(session.userId))
            throw new ProtocolException("SELF_PRIVATE_NOT_ALLOWED", "不能给自己发私聊");
        ClientSession target = registry.findReady(request.to());
        if (target == null && !server.social().friends(session.userId, request.to()))
            throw new ProtocolException("USER_OFFLINE", "对方离线，只能给好友发送离线消息");
        if (!server.social().privateAllowed(session.userId, request.to()))
            throw new ProtocolException("PRIVATE_FORBIDDEN", "请先取得私聊许可或成为好友");
        if (request.replyTo() != null)
            server.archive().requireVisibleReply(session.userId, ConversationScope.privateChat(session.userId, request.to()), request.replyTo());
        return new ChatRoute(null, target);
    }

    void checkAttachmentUpload(ClientSession session, Message request) throws IOException {
        synchronized (mutationOrder) { routeChat(session, request); }
    }

    @FunctionalInterface interface StoreAttachment { void commit() throws IOException; }

    void publishAttachment(ClientSession owner, ClientSession transfer, Message request, StoreAttachment storage) throws IOException {
        synchronized (mutationOrder) {
            if (!owner.ready || owner.isClosed() || transfer.isClosed())
                throw new ProtocolException("NOT_AUTHENTICATED", "传输或登录已结束");
            ChatRoute route = routeChat(owner, request);
            storage.commit();
            try { publishChat(owner, request, route); }
            catch (ProtocolException ex) { throw ex; }
            catch (IOException ex) {
                throw new ProtocolException("DELIVERY_UNCONFIRMED", "附件可能已归档，请核对聊天记录");
            }
        }
    }

    private void publishChat(ClientSession session, Message request, ChatRoute route) throws IOException {
        String nickname = server.accounts().nickname(session.userId);
        if (route.group() != null) {
            Message event = server.archive().record(MessageType.GROUP_MESSAGE, session.userId, nickname, request, List.of());
            server.broadcast(event, route.group());
        } else {
            Message event = server.archive().record(MessageType.PRIVATE_MESSAGE, session.userId, nickname, request,
                    List.of(session.userId, request.to()));
            if (route.target() != null && !server.deliver(route.target(), event))
                throw new ProtocolException("DELIVERY_UNCONFIRMED", "私聊写入中断，消息可能已归档，请核对记录");
            server.deliver(session, event);
        }
    }

    private void login(ClientSession session, Message request) throws IOException {
        Validation.requestId(request.requestId());
        String userId = server.authenticate(session, request,
                () -> server.accounts().authenticate(request.userId(), request.password()));
        if (!registry.reserve(userId, session))
            throw new ProtocolException("USER_EXISTS", "用户标识已在线，请换一个名称");
        // Reserved sessions are invisible to routing until LOGIN_OK has been flushed.
        if (!server.deliverAndWait(session, profileMessage(MessageType.LOGIN_OK, request.requestId(), server.accounts().profile(userId)))) {
            session.close("登录响应发送失败");
            return;
        }
        Message list = registry.activate(session);
        if (list == null) return;
        server.authenticated(session);
        LOG.info(() -> "登录成功 user=" + userId);
        if (session.loginDeadline != null) session.loginDeadline.cancel(false);
        session.heartbeat.start();
        server.broadcast(list);
        for (ClientSession online : registry.readySessions()) {
            Message profile = displayMessage(online.userId);
            if (profile != null && !server.deliverAndWait(session, profile)) {
                session.close("初始资料发送失败");
                return;
            }
        }
        Message display = displayMessage(userId);
        if (display != null) server.broadcast(display);
        server.system(userId + " 上线了");
    }

    private void export(ClientSession session, Message request) throws IOException {
        Validation.requestId(request.requestId());
        if (request.userId() != null && !request.userId().equals(session.userId))
            throw new ProtocolException("FORBIDDEN", "不能导出其他用户的记录");
        synchronized (session) {
            if (session.isClosed()) throw new ProtocolException("NOT_AUTHENTICATED", "登录态已失效");
            if (request.cursor() == null) {
                ConversationScope.validate(session.userId, request.conversationId());
                session.closeExport(); session.exportCursor = server.archive().open(session.userId, request.conversationId());
            } else if (session.exportCursor == null || !session.exportCursor.token.equals(request.cursor()))
                throw new ProtocolException("FORBIDDEN", "导出游标无效或已过期");
            if (!java.util.Objects.equals(session.exportCursor.scope, request.conversationId()))
                throw new ProtocolException("FORBIDDEN", "导出期间不能切换会话范围");
            ChatRecord record = session.exportCursor.next();
            if (record == null) {
                session.closeExport();
                server.deliver(session, Message.of(MessageType.EXPORT_END).requestId(request.requestId()).build());
            } else server.deliver(session, Message.of(MessageType.EXPORT_PAGE).requestId(request.requestId())
                    .cursor(session.exportCursor.token).record(record).build());
        }
    }
    private static void ownArchive(ClientSession session, Message request) throws ProtocolException {
        if (request.userId() != null && !request.userId().equals(session.userId))
            throw new ProtocolException("FORBIDDEN", "只能操作本人聊天记录");
    }

    private void social(ClientSession session, Message request) throws IOException {
        Validation.requestId(request.requestId());
        if (request.userId() != null && !session.userId.equals(request.userId()))
            throw new ProtocolException("FORBIDDEN", "只能操作当前账号");
        SocialAction action;
        try { action = SocialAction.valueOf(request.socialAction()); }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new ProtocolException("BAD_MESSAGE", "无效的社交操作");
        }
        List<SocialItem> rows;
        switch (action) {
            case STATE -> rows = server.social().items(session.userId, server.accounts());
            case FIND_GROUP -> rows = server.social().directory(request.content(), session.userId);
            case FIND_USER -> {
                String user = SocialStore.existingUser(server.accounts(), request.to());
                rows = List.of(SocialItem.user(SocialItem.Kind.USER, user, server.accounts().nickname(user)));
            }
            default -> {
                Set<String> affected = server.social().change(session.userId, action, request, server.accounts(),
                        user -> registry.findReady(user) != null);
                server.deliver(session, Message.of(MessageType.SOCIAL_END).requestId(request.requestId()).build());
                String notice = switch (action) {
                    case FRIEND_ACCEPT -> "好友申请已获接受";
                    case FRIEND_REJECT -> "好友申请已被拒绝";
                    case PRIVATE_ACCEPT -> "私聊请求已获接受";
                    case PRIVATE_REJECT -> "私聊请求已被拒绝";
                    default -> null;
                };
                for (String user : affected) {
                    ClientSession peer = registry.findReady(user);
                    if (peer != null) server.deliver(peer, Message.of(MessageType.SOCIAL_CHANGED)
                            .message(peer == session ? null : notice).build());
                }
                return;
            }
        }
        String cursor = request.cursor();
        if (cursor != null && cursor.length() > 80) throw new ProtocolException("BAD_MESSAGE", "无效查询游标");
        List<SocialItem> page = rows.stream().filter(row -> cursor == null || row.key().compareTo(cursor) > 0)
                .sorted(Comparator.comparing(SocialItem::key)).limit(SocialAction.PAGE_SIZE + 1L).toList();
        int count = Math.min(SocialAction.PAGE_SIZE, page.size());
        for (int i = 0; i < count; i++) {
            if (!server.deliver(session, Message.of(MessageType.SOCIAL_ROW).requestId(request.requestId()).socialItem(page.get(i)).build()))
                return;
        }
        server.deliver(session, Message.of(MessageType.SOCIAL_END).requestId(request.requestId())
                .cursor(page.size() > count ? page.get(count - 1).key() : null).build());
    }
    private void history(ClientSession session, Message request) throws IOException {
        Validation.requestId(request.requestId()); ownArchive(session, request);
        synchronized (session) {
            if (request.cursor() == null) {
                session.closeHistory(); session.historyCursor = server.archive().open(session.userId, request.conversationId());
            } else if (session.historyCursor == null || !session.historyCursor.token.equals(request.cursor())
                    || !java.util.Objects.equals(session.historyCursor.scope, request.conversationId()))
                throw new ProtocolException("FORBIDDEN", "历史游标无效或会话范围不匹配");
            ChatRecord record = session.historyCursor.next();
            if (record == null) {
                session.closeHistory(); server.deliver(session, Message.of(MessageType.HISTORY_END).requestId(request.requestId()).build());
            } else {
                sendDisplayProfile(session, record.sender());
                if (record.recipient() != null) sendDisplayProfile(session, record.recipient());
                server.deliver(session, Message.of(MessageType.HISTORY_PAGE).requestId(request.requestId())
                        .cursor(session.historyCursor.token).nickname(server.accounts().nickname(record.sender())).record(record).build());
            }
        }
    }

    private void historyView(ClientSession session, Message request) throws IOException {
        Validation.requestId(request.requestId());
        ownArchive(session, request);
        HistoryQuery mode;
        try { mode = HistoryQuery.valueOf(request.historyMode()); }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new ProtocolException("BAD_MESSAGE", "无效的历史浏览方式");
        }
        ArchiveView.Page page = new ArchiveView(server.archive(), server.accounts()).read(session.userId, mode, request);
        if (mode != HistoryQuery.SEARCH) {
            Set<String> profiles = new HashSet<>();
            for (ChatRecord row : page.rows()) {
                profiles.add(row.sender());
                if (row.recipient() != null) profiles.add(row.recipient());
            }
            for (String user : profiles) {
                Message display = displayMessage(user);
                if (display != null && !server.deliverAndWait(session, display)) {
                    session.close("历史资料发送失败");
                    return;
                }
            }
        }
        for (ChatRecord row : page.rows()) {
            if (!server.deliver(session, Message.of(MessageType.HISTORY_VIEW_ROW).requestId(request.requestId())
                    .record(row).nickname(server.accounts().nickname(row.sender())).build())) return;
        }
        server.deliver(session, Message.of(MessageType.HISTORY_VIEW_END).requestId(request.requestId()).cursor(page.next()).build());
    }

    private static void validateChat(Message request) throws ProtocolException {
        Validation.requestId(request.requestId());
        Validation.content(request.content());
        if (request.attachment() != null) throw new ProtocolException("BAD_MESSAGE", "附件需要独立上传");
    }
    private static Message profileMessage(MessageType type, String requestId, UserProfile profile) {
        return Message.of(type).requestId(requestId).userId(profile.userId()).nickname(profile.nickname())
                .avatar(profile.avatar()).createdAt(profile.createdAt()).build();
    }
    private void sendDisplayProfile(ClientSession recipient, String userId) {
        Message display = displayMessage(userId);
        if (display != null) server.deliver(recipient, display);
    }

    private Message displayMessage(String userId) {
        AccountStore.DisplayProfile profile = server.accounts().displayProfile(userId);
        return profile == null ? null : Message.of(MessageType.DISPLAY_PROFILE).userId(userId)
                .nickname(profile.nickname()).avatar(profile.avatar()).revision(profile.revision()).build();
    }

}
