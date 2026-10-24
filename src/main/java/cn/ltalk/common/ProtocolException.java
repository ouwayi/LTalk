package cn.ltalk.common;

import java.io.IOException;

public final class ProtocolException extends IOException {
    private final String code;
    private final String requestId;
    public ProtocolException(String code, String message) { this(code, message, null); }
    public ProtocolException(String code, String message, String requestId) {
        super(message); this.code = code; this.requestId = requestId;
    }
    public String code() { return code; }
    public String requestId() { return requestId; }
}
