package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

final class MessageRouter {
    private static final Logger LOG = Logger.getLogger(MessageRouter.class.getName());
    private final ChatServer server;
    private final SessionRegistry registry;
    private final Object mutationOrder = new Object();

    MessageRouter(ChatServer server, SessionRegistry registry) { this.server = server; this.registry = registry; }

    void handle(ClientSession session, Message request) {
        if (request.type() == MessageType.GROUP_SEND || request.type() == MessageType.PRIVATE_SEND || request.type() == MessageType.HISTORY_DELETE) {
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
                if (session.loginDeadline != null) session.loginDeadline.cancel(false);
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
                String id = server.accounts().register(request.userId(), request.nickname(), request.password(), "RANDOM".equals(request.usernameMode()));
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
                    server.accounts().changePassword(session.userId, request.password(), request.newPassword());
                    server.deliver(session, Message.of(MessageType.PASSWORD_CHANGED).requestId(request.requestId()).build());
                }
                case GROUP_SEND -> {
                    validateChat(request);
                    var recipients = registry.readySessions();
                    Message event = server.archive().record(MessageType.GROUP_MESSAGE, session.userId, server.accounts().nickname(session.userId), request,
                            recipients.stream().map(s -> s.userId).toList());
                    for (ClientSession recipient : recipients) server.deliver(recipient, event);
                }
                case PRIVATE_SEND -> {
                    validateChat(request);
                    if (request.to() == null || !request.to().matches("[A-Za-z0-9_]{1,20}"))
                        throw new ProtocolException("BAD_MESSAGE", "私聊需要合法的 to 用户标识");
                    if (request.to().equals(session.userId))
                        throw new ProtocolException("SELF_PRIVATE_NOT_ALLOWED", "不能给自己发私聊");
                    ClientSession target = registry.findReady(request.to());
                    if (target == null)
                        throw new ProtocolException("USER_OFFLINE", "目标用户不在线，请重新选择");
                    Message event = server.archive().record(MessageType.PRIVATE_MESSAGE, session.userId, server.accounts().nickname(session.userId), request,
                            List.of(session.userId, target.userId));
                    if (!server.deliver(target, event)) throw new ProtocolException("USER_OFFLINE", "目标用户不在线，请重新选择");
                    server.deliver(session, event);
                }
                default -> throw new ProtocolException("BAD_MESSAGE", "此消息类型不能由客户端发送");
            }
        } catch (ProtocolException ex) {
            LOG.info(() -> "请求失败 user=" + session.userId + " type=" + request.type() + " code=" + ex.code());
            String requestId = request.requestId();
            if (requestId != null && (requestId.isBlank() || requestId.length() > 64)) requestId = null;
            server.deliver(session, Message.error(requestId, ex.code(), ex.getMessage()));
            if (request.type() == MessageType.LOGIN && session.userId == null && !session.greeted) session.close("登录失败：" + ex.code());
        } catch (IOException ex) {
            if (request.type() == MessageType.EXPORT_REQUEST) session.closeExport();
            if (request.type() == MessageType.HISTORY_REQUEST) session.closeHistory();
            server.deliver(session, Message.error(request.requestId(), request.type() == MessageType.EXPORT_REQUEST ? "EXPORT_FAILED" : "STORAGE_ERROR",
                    "服务端文件操作失败，请稍后重试"));
        }
    }

    private void login(ClientSession session, Message request) throws IOException {
        Validation.requestId(request.requestId());
        String userId = server.accounts().authenticate(request.userId(), request.password());
        if (!registry.reserve(userId, session))
            throw new ProtocolException("USER_EXISTS", "用户标识已在线，请换一个名称");
        // Reserved sessions are invisible to routing until LOGIN_OK has been flushed.
        if (!server.deliver(session, profileMessage(MessageType.LOGIN_OK, request.requestId(), server.accounts().profile(userId)))) return;
        Message list = registry.activate(session);
        if (list == null) return;
        LOG.info(() -> "登录成功 user=" + userId);
        if (session.loginDeadline != null) session.loginDeadline.cancel(false);
        session.heartbeat.start();
        server.broadcast(list);
        for (ClientSession online : registry.readySessions()) sendDisplayProfile(session, online.userId);
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

    private static void validateChat(Message request) throws ProtocolException {
        Validation.requestId(request.requestId());
        Validation.content(request.content());
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
