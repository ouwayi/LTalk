package cn.ltalk.common;

import com.google.gson.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Set;

/** One UTF-8 JSON object per LF-delimited frame; never buffers more than 16 KiB. */
public final class MessageCodec {
    public static final int MAX_FRAME_BYTES = 16 * 1024;
    private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).disableHtmlEscaping().create();
    private static final Set<String> STRING_FIELDS = Set.of("requestId", "userId", "from", "to", "content",
            "serverTime", "code", "message", "nickname", "password", "usernameMode", "messageId", "conversationId", "cursor");

    private MessageCodec() { }

    public static String encode(Message message) throws ProtocolException {
        String json = GSON.toJson(message);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES)
            throw new ProtocolException("FRAME_TOO_LARGE", "JSON 帧超过 16 KiB，请缩短消息");
        return json;
    }

    public static Message decode(String frame) throws ProtocolException {
        String requestId = null;
        try {
            JsonElement element = GSON.fromJson(frame, JsonElement.class);
            if (element == null || !element.isJsonObject()) throw new IllegalArgumentException();
            JsonObject object = element.getAsJsonObject();
            if (isString(object.get("requestId"))) {
                String candidate = object.get("requestId").getAsString();
                if (!candidate.isBlank() && candidate.length() <= 64) requestId = candidate;
            }
            if (!isString(object.get("type"))) throw new IllegalArgumentException();
            MessageType.valueOf(object.get("type").getAsString());
            for (String key : STRING_FIELDS) {
                if (object.has(key) && !isString(object.get(key))) throw new IllegalArgumentException();
            }
            if (object.has("users")) {
                if (!object.get("users").isJsonArray()) throw new IllegalArgumentException();
                for (JsonElement user : object.getAsJsonArray("users"))
                    if (!isString(user)) throw new IllegalArgumentException();
            }
            if (object.has("revision")) {
                JsonElement revision = object.get("revision");
                if (!revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber()
                        || !revision.toString().matches("[0-9]+")) throw new IllegalArgumentException();
                if (revision.getAsBigDecimal().longValueExact() < 0) throw new IllegalArgumentException();
            }
            return GSON.fromJson(object, Message.class);
        } catch (RuntimeException ex) {
            throw new ProtocolException("BAD_MESSAGE", "无效 JSON、未知类型或字段类型错误", requestId);
        }
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    public static void write(OutputStream output, Message message) throws IOException {
        byte[] bytes = encode(message).getBytes(StandardCharsets.UTF_8);
        output.write(bytes);
        output.write('\n');
        output.flush();
    }

    public static final class FrameReader {
        private final InputStream input;
        public FrameReader(InputStream input) { this.input = new BufferedInputStream(input); }

        /** null means clean EOF; an unterminated partial frame is a connection error. */
        public String readFrame() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (true) {
                int value = input.read();
                if (value == -1) {
                    if (bytes.size() == 0) return null;
                    throw new EOFException("连接在完整帧结束前断开");
                }
                if (value == '\n') break;
                if (bytes.size() == MAX_FRAME_BYTES)
                    throw new ProtocolException("FRAME_TOO_LARGE", "JSON 帧超过 16 KiB，连接已关闭");
                bytes.write(value);
            }
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            } catch (CharacterCodingException ex) {
                throw new ProtocolException("BAD_MESSAGE", "消息必须使用合法 UTF-8 编码");
            }
        }

        public Message read() throws IOException {
            String frame = readFrame();
            return frame == null ? null : decode(frame);
        }
    }
}
