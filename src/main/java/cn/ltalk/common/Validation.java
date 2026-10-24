package cn.ltalk.common;

public final class Validation {
    public static final int MAX_CONTENT_CHARS = 2000;
    private Validation() { }

    public static String userId(String value) throws ProtocolException {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("[A-Za-z0-9_]{1,20}"))
            throw new ProtocolException("INVALID_USER_ID", "用户标识须为 1～20 位英文字母、数字或下划线");
        return normalized;
    }

    public static void content(String value) throws ProtocolException {
        if (value == null) throw new ProtocolException("BAD_MESSAGE", "缺少 content 字段");
        if (value.isBlank()) throw new ProtocolException("EMPTY_CONTENT", "消息不能全为空白");
        if (value.length() > MAX_CONTENT_CHARS)
            throw new ProtocolException("CONTENT_TOO_LONG", "消息正文最多 2000 个 Java char 单元");
    }

    public static void requestId(String value) throws ProtocolException {
        if (value == null || value.isBlank() || value.length() > 64)
            throw new ProtocolException("BAD_MESSAGE", "requestId 须为 1～64 个字符且不能全为空白");
    }
}
