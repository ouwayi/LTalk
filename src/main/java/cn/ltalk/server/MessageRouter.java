package cn.ltalk.server;

import cn.ltalk.common.*;

import java.time.Instant;
import java.util.logging.Logger;

final class MessageRouter {
    private static final Logger LOG = Logger.getLogger(MessageRouter.class.getName());
    private final ChatServer server;
    private final SessionRegistry registry;

    MessageRouter(ChatServer server, SessionRegistry registry) { this.server = server; this.registry = registry; }

    void handle(ClientSession session, Message request) {
        try {
            if (request.type() == MessageType.LOGIN) {
                if (session.userId != null)
                    throw new ProtocolException("ALREADY_LOGGED_IN", "当前连接不能重复登录");
                login(session, request);
                return;
            }
            if (!session.ready) throw new ProtocolException("NOT_LOGGED_IN", "请先发送 LOGIN 登录");
            switch (request.type()) {
                case PING, PONG -> session.heartbeat.receive(request);
                case LOGOUT -> session.close("用户主动退出");
                case GROUP_SEND -> {
                    validateChat(request);
                    server.broadcast(chatEvent(MessageType.GROUP_MESSAGE, session, request));
                }
                case PRIVATE_SEND -> {
                    validateChat(request);
                    if (request.to() == null || !request.to().matches("[A-Za-z0-9_]{1,20}"))
                        throw new ProtocolException("BAD_MESSAGE", "私聊需要合法的 to 用户标识");
                    if (request.to().equals(session.userId))
                        throw new ProtocolException("SELF_PRIVATE_NOT_ALLOWED", "不能给自己发私聊");
                    ClientSession target = registry.findReady(request.to());
                    Message event = chatEvent(MessageType.PRIVATE_MESSAGE, session, request);
                    if (target == null || !server.deliver(target, event))
                        throw new ProtocolException("USER_OFFLINE", "目标用户不在线，请重新选择");
                    server.deliver(session, event);
                }
                default -> throw new ProtocolException("BAD_MESSAGE", "此消息类型不能由客户端发送");
            }
        } catch (ProtocolException ex) {
            LOG.info(() -> "请求失败 user=" + session.userId + " type=" + request.type() + " code=" + ex.code());
            String requestId = request.requestId();
            if (requestId != null && (requestId.isBlank() || requestId.length() > 64)) requestId = null;
            server.deliver(session, Message.error(requestId, ex.code(), ex.getMessage()));
            if (request.type() == MessageType.LOGIN && session.userId == null) session.close("登录失败：" + ex.code());
        }
    }

    private void login(ClientSession session, Message request) throws ProtocolException {
        Validation.requestId(request.requestId());
        String userId = Validation.userId(request.userId());
        if (!registry.reserve(userId, session))
            throw new ProtocolException("USER_EXISTS", "用户标识已在线，请换一个名称");
        // Reserved sessions are invisible to routing until LOGIN_OK has been flushed.
        if (!server.deliver(session, Message.of(MessageType.LOGIN_OK).requestId(request.requestId()).userId(userId).build())) return;
        Message list = registry.activate(session);
        if (list == null) return;
        LOG.info(() -> "登录成功 user=" + userId);
        if (session.loginDeadline != null) session.loginDeadline.cancel(false);
        session.heartbeat.start();
        server.broadcast(list);
        server.system(userId + " 上线了");
    }

    private static void validateChat(Message request) throws ProtocolException {
        Validation.requestId(request.requestId());
        Validation.content(request.content());
    }

    private static Message chatEvent(MessageType type, ClientSession session, Message request) {
        return Message.of(type).requestId(request.requestId()).from(session.userId)
                .to(type == MessageType.PRIVATE_MESSAGE ? request.to() : null)
                .content(request.content()).serverTime(Instant.now().toString()).build();
    }
}
