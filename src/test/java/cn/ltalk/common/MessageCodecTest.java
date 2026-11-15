package cn.ltalk.common;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MessageCodecTest {
    @Test void roundTripKeepsChineseEmojiNewlinesAndPlainText() throws Exception {
        Message original = Message.of(MessageType.GROUP_MESSAGE).requestId("r2").from("alice")
                .content("你好🙂\n第二行 <html> & \\\"测试\"").serverTime("2026-10-24T10:00:00Z").build();
        String encoded = MessageCodec.encode(original);
        assertFalse(encoded.contains("\n"));
        assertEquals(original, MessageCodec.decode(encoded));
    }

    @Test void mergedFramesAndFragmentedUtf8AreReadExactlyOnce() throws Exception {
        String data = "{\"type\":\"GROUP_SEND\",\"content\":\"中文\\n下一行\"}\n{\"type\":\"LOGOUT\"}\n";
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        InputStream fragmented = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, 1));
            }
        };
        MessageCodec.FrameReader reader = new MessageCodec.FrameReader(fragmented);
        assertEquals("中文\n下一行", reader.read().content());
        assertEquals(MessageType.LOGOUT, reader.read().type());
        assertNull(reader.read());
    }

    @Test void rejectsMalformedJsonAndWrongFieldTypes() {
        for (String value : new String[]{"", "[]", "null", "{", "{type:'LOGIN'}", "{\"type\":\"BOGUS\"}",
                "{\"type\":1}", "{\"type\":\"LOGIN\",\"userId\":123}", "{\"type\":\"LOGIN\",\"requestId\":null}",
                "{\"type\":\"PASSWORD_CHANGE\",\"newPassword\":42}",
                "{\"type\":\"USER_LIST\",\"users\":[1]}", "{\"type\":\"USER_LIST\",\"revision\":1.2}",
                "{\"type\":\"USER_LIST\",\"revision\":9223372036854775808}", "{\"type\":\"LOGOUT\"} {}"}) {
            assertEquals("BAD_MESSAGE", assertThrows(ProtocolException.class, () -> MessageCodec.decode(value), value).code());
        }
    }

    @Test void rejectsOversizedFrameBeforeNewlineAndAcceptsExactByteBoundary() throws Exception {
        byte[] exact = ("x".repeat(MessageCodec.MAX_FRAME_BYTES) + "\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(MessageCodec.MAX_FRAME_BYTES, new MessageCodec.FrameReader(new ByteArrayInputStream(exact)).readFrame().length());
        byte[] tooLong = "x".repeat(MessageCodec.MAX_FRAME_BYTES + 1).getBytes(StandardCharsets.UTF_8);
        assertEquals("FRAME_TOO_LARGE", assertThrows(ProtocolException.class,
                () -> new MessageCodec.FrameReader(new ByteArrayInputStream(tooLong)).readFrame()).code());
        Message multibyte = Message.of(MessageType.GROUP_SEND).content("中".repeat(6000)).build();
        assertThrows(ProtocolException.class, () -> MessageCodec.encode(multibyte));
    }

    @Test void rejectsInvalidUtf8AndTruncatedFrames() {
        assertEquals("BAD_MESSAGE", assertThrows(ProtocolException.class,
                () -> new MessageCodec.FrameReader(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28, '\n'})).readFrame()).code());
        assertThrows(EOFException.class,
                () -> new MessageCodec.FrameReader(new ByteArrayInputStream(new byte[]{'{'})).readFrame());
    }

    @Test void onlyAvatarFramesCanUseTheLargerBound() throws Exception {
        Message profile = Message.of(MessageType.DISPLAY_PROFILE).userId("bob").nickname("昵称")
                .avatar("a".repeat(174_764)).revision(1).build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MessageCodec.write(bytes, profile);
        assertTrue(bytes.size() > MessageCodec.MAX_FRAME_BYTES);
        assertEquals(profile, new MessageCodec.FrameReader(new ByteArrayInputStream(bytes.toByteArray())).read());

        String prefix = "{\"type\":\"PROFILE_UPDATE\",\"avatar\":\"";
        String over = prefix + "a".repeat(MessageCodec.MAX_AVATAR_FRAME_BYTES);
        assertEquals("FRAME_TOO_LARGE", assertThrows(ProtocolException.class, () ->
                new MessageCodec.FrameReader(new ByteArrayInputStream(over.getBytes(StandardCharsets.US_ASCII))).read()).code());
        String disguised = prefix + "a".repeat(MessageCodec.MAX_FRAME_BYTES) + "\",\"type\":\"GROUP_SEND\"}\n";
        assertEquals("FRAME_TOO_LARGE", assertThrows(ProtocolException.class, () ->
                new MessageCodec.FrameReader(new ByteArrayInputStream(disguised.getBytes(StandardCharsets.US_ASCII))).read()).code());
    }

    @Test void validatesIdentityAndContentBoundariesInJavaCharUnits() throws Exception {
        assertEquals("alice", Validation.userId("  alice  "));
        assertEquals("A_1", Validation.userId("A_1"));
        for (String invalid : new String[]{"", " ", "中文", "a-b", "a".repeat(21)})
            assertEquals("INVALID_USER_ID", assertThrows(ProtocolException.class, () -> Validation.userId(invalid)).code());
        Validation.content("中".repeat(2000));
        Validation.content("🙂".repeat(1000));
        assertEquals("CONTENT_TOO_LONG", assertThrows(ProtocolException.class, () -> Validation.content("🙂".repeat(1001))).code());
        assertEquals("EMPTY_CONTENT", assertThrows(ProtocolException.class, () -> Validation.content(" \t\n\u3000")).code());
        assertEquals("BAD_MESSAGE", assertThrows(ProtocolException.class, () -> Validation.content(null)).code());
    }
}
