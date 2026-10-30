package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.client.AvatarImporter;
import cn.ltalk.support.Peer;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class ProfileConnectionTest {
    @TempDir Path directory;
    @Test void helloKeepsUnauthenticatedConnectionAliveWithoutGrantingBusinessAccessAndAllowsRetry() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0, 3, Duration.ofMillis(250), new HeartbeatSettings(80, 800))) {
            server.start();
            try (Peer peer = new Peer(server.port())) {
                peer.send(Message.of(MessageType.HELLO).requestId("hello").build()); peer.type(MessageType.HELLO_OK);
                peer.send(Message.of(MessageType.PROFILE_GET).requestId("blocked").build()); peer.error("NOT_AUTHENTICATED");
                peer.send(Message.of(MessageType.EXPORT_REQUEST).requestId("export").build()); peer.error("NOT_AUTHENTICATED");
                peer.group("blocked"); peer.error("NOT_AUTHENTICATED");
                assertEquals(0, server.onlineCount());
                // Peer responds to probes while reading; wait beyond the original absolute login deadline.
                peer.assertNo(m -> m.type() == MessageType.USER_LIST || m.type() == MessageType.GROUP_MESSAGE, 400);
                peer.send(Message.of(MessageType.LOGIN).requestId("bad").userId("absent").password("wrong!12").build()); peer.error("LOGIN_FAILED");
                peer.send(Message.of(MessageType.REGISTER).requestId("r").userId("alice").nickname("昵称").password("pass!12").build()); peer.type(MessageType.REGISTER_OK);
                peer.send(Message.of(MessageType.LOGIN).requestId("l").userId("alice").password("pass!12").build());
                Message loggedIn = peer.type(MessageType.LOGIN_OK); assertEquals("昵称", loggedIn.nickname()); assertNotNull(loggedIn.createdAt());
                peer.group("after login"); peer.type(MessageType.GROUP_MESSAGE);
            }
        }
    }
    @Test void profilePermissionsValidationPersistenceAndAvatarReset() throws Exception {
        Path image = directory.resolve("avatar.png"); ImageIO.write(new BufferedImage(90, 60, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        String avatar = AvatarImporter.load(image); String created;
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory.resolve("server")); server.start();
            try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob")) {
                a.send(Message.of(MessageType.PROFILE_GET).requestId("p").build()); Message original = a.type(MessageType.PROFILE_OK); created = original.createdAt();
                assertNull(original.password()); assertNotNull(created);
                a.send(Message.of(MessageType.PROFILE_UPDATE).requestId("forged").userId("bob").nickname("冒名").avatar(avatar).build()); a.error("FORBIDDEN");
                a.send(Message.of(MessageType.PROFILE_GET).requestId("get-other").userId("bob").build()); a.error("FORBIDDEN");
                for (Message bad : List.of(
                        Message.of(MessageType.PROFILE_UPDATE).requestId("empty").nickname(" ").avatar(avatar).build(),
                        Message.of(MessageType.PROFILE_UPDATE).requestId("long").nickname("长".repeat(41)).avatar(avatar).build(),
                        Message.of(MessageType.PROFILE_UPDATE).requestId("bad-avatar").nickname("新昵称").avatar("not-base64").build())) {
                    a.send(bad); a.type(MessageType.ERROR);
                }
                a.send(Message.of(MessageType.PROFILE_GET).requestId("unchanged").build()); assertEquals(original.nickname(), a.type(MessageType.PROFILE_OK).nickname());
                a.send(Message.of(MessageType.PROFILE_UPDATE).requestId("save").nickname(" 新昵称🙂 ").avatar(avatar).build());
                Message saved = a.type(MessageType.PROFILE_OK); assertEquals("新昵称🙂", saved.nickname()); assertEquals(avatar, saved.avatar()); assertEquals(created, saved.createdAt());
                a.privateTo("bob", "stable route"); assertEquals("alice", b.type(MessageType.PRIVATE_MESSAGE).from()); a.type(MessageType.PRIVATE_MESSAGE);
                b.assertNo(m -> m.type() == MessageType.PROFILE_OK, 80);
            }
        }
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory.resolve("server")); server.start();
            try (Peer a = Peer.login(server.port(), "alice")) {
                a.send(Message.of(MessageType.PROFILE_GET).requestId("reload").build()); Message loaded = a.type(MessageType.PROFILE_OK);
                assertEquals("新昵称🙂", loaded.nickname()); assertEquals(avatar, loaded.avatar()); assertEquals(created, loaded.createdAt());
                a.send(Message.of(MessageType.PROFILE_UPDATE).requestId("reset").nickname(loaded.nickname()).avatar("").build()); assertEquals("", a.type(MessageType.PROFILE_OK).avatar());
            }
        }
    }
    @Test void legacyAccountLoadsAndFailedSaveKeepsPreviousProfileAndPassword() throws Exception {
        Path accounts = directory.resolve("accounts"); AccountStore store = new AccountStore(accounts);
        store.register("alice", "旧昵称", "pass!12", false);
        Path file = accounts.resolve(AccountStore.fileKey("alice") + ".json");
        JsonObject old = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); old.remove("avatar"); old.remove("createdAt"); Files.writeString(file, old.toString());
        store = new AccountStore(accounts); assertNull(store.profile("alice").createdAt()); assertEquals("", store.profile("alice").avatar());
        Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("block"), "force replacement failure");
        AccountStore loaded = store;
        assertThrows(java.io.IOException.class, () -> loaded.updateProfile("alice", "不能落盘", ""));
        assertEquals("旧昵称", store.profile("alice").nickname()); assertEquals("alice", store.authenticate("alice", "pass!12"));
        assertEquals(old.get("hash").getAsString().length(), 44);
    }
    @Test void avatarBoundsAndWireTypesAreValidatedBeforeDecodingLargeImages() throws Exception {
        assertEquals("INVALID_AVATAR", assertThrows(ProtocolException.class, () -> AvatarData.decode("a".repeat(14000))).code());
        Path wrongSize = directory.resolve("large.png"); ImageIO.write(new BufferedImage(49, 48, BufferedImage.TYPE_INT_RGB), "png", wrongSize.toFile());
        assertThrows(ProtocolException.class, () -> AvatarData.decode(Base64.getEncoder().encodeToString(Files.readAllBytes(wrongSize))));
        assertThrows(ProtocolException.class, () -> MessageCodec.decode("{\"type\":\"PROFILE_UPDATE\",\"avatar\":42}"));
        Path broken = directory.resolve("broken.png"); Files.writeString(broken, "broken"); assertThrows(java.io.IOException.class, () -> AvatarImporter.load(broken));
        String converted = AvatarImporter.load(wrongSize); assertEquals(48, AvatarData.decode(converted).getWidth());
        assertTrue(MessageCodec.encode(Message.of(MessageType.PROFILE_OK).userId("alice").nickname("昵称").avatar(converted).build()).length() < MessageCodec.MAX_FRAME_BYTES);
    }
}
