package cn.ltalk.common;

/** Stable archive scope, independent of Swing selection and display names. */
public final class ConversationScope {
    public static final String PUBLIC = "GROUP:PUBLIC";
    private ConversationScope() { }
    public static String privateChat(String self, String peer) throws ProtocolException {
        self = Validation.userId(self); peer = Validation.userId(peer);
        if (self.equals(peer)) throw new ProtocolException("BAD_MESSAGE", "不能选择与自己的私聊");
        return "PRIVATE:" + (self.compareTo(peer) < 0 ? self + ":" + peer : peer + ":" + self);
    }
    public static void validate(String self, String scope) throws ProtocolException {
        if (scope == null || PUBLIC.equals(scope)) return; // Legacy full-account export API.
        String[] parts = scope.split(":", -1);
        if (parts.length != 3 || !parts[0].equals("PRIVATE")
                || !(self.equals(parts[1]) || self.equals(parts[2])))
            throw new ProtocolException("FORBIDDEN", "无权导出此会话");
        String canonical = privateChat(parts[1], parts[2]);
        if (!scope.equals(canonical)) throw new ProtocolException("BAD_MESSAGE", "无效会话标识");
    }
}
