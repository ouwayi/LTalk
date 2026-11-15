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
        Path image = directory.resolve("avatar.png"); writeDetailedPhoto(image);
        String avatar = AvatarImporter.load(image); String created;
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory.resolve("server")); server.start();
            try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob")) {
                Message initial = b.await(m -> m.type() == MessageType.DISPLAY_PROFILE && "alice".equals(m.userId()));
                assertEquals("alice", initial.nickname()); assertEquals("", initial.avatar()); assertEquals(0L, initial.revision());
                assertNull(initial.createdAt()); assertNull(initial.password());
                assertEquals("bob", a.await(m -> m.type() == MessageType.DISPLAY_PROFILE && "bob".equals(m.userId())).nickname());
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
                Message shared = b.await(m -> m.type() == MessageType.DISPLAY_PROFILE && "alice".equals(m.userId()));
                assertEquals(saved.nickname(), shared.nickname()); assertEquals(avatar, shared.avatar());
                assertTrue(shared.revision() > initial.revision()); assertNull(shared.createdAt());
                b.send(Message.of(MessageType.DISPLAY_PROFILE).requestId("fake").userId("alice").nickname("冒名").avatar("").revision(99).build());
                b.error("BAD_MESSAGE");
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
        JsonObject old = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        old.remove("avatar"); old.remove("createdAt"); old.remove("profileRevision"); Files.writeString(file, old.toString());
        store = new AccountStore(accounts); assertNull(store.profile("alice").createdAt()); assertEquals("", store.profile("alice").avatar());
        Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("block"), "force replacement failure");
        AccountStore loaded = store;
        assertThrows(java.io.IOException.class, () -> loaded.updateProfile("alice", "不能落盘", ""));
        assertThrows(java.io.IOException.class, () -> loaded.changePassword("alice", "pass!12", "newpass!"));
        assertEquals("LOGIN_FAILED", assertThrows(ProtocolException.class, () -> loaded.authenticate("alice", "newpass!")).code());
        assertEquals("旧昵称", store.profile("alice").nickname()); assertEquals("alice", store.authenticate("alice", "pass!12"));
        assertEquals(old.get("hash").getAsString().length(), 44);
    }
    @Test void avatarBoundsAndWireTypesAreValidatedBeforeDecodingLargeImages() throws Exception {
        assertEquals("INVALID_AVATAR", assertThrows(ProtocolException.class,
                () -> AvatarData.decode("a".repeat(((AvatarData.MAX_BYTES + 2) / 3) * 4 + 4))).code());
        Path wrongSize = directory.resolve("large.png"); ImageIO.write(new BufferedImage(49, 48, BufferedImage.TYPE_INT_RGB), "png", wrongSize.toFile());
        assertThrows(ProtocolException.class, () -> AvatarData.decode(Base64.getEncoder().encodeToString(Files.readAllBytes(wrongSize))));
        assertThrows(ProtocolException.class, () -> MessageCodec.decode("{\"type\":\"PROFILE_UPDATE\",\"avatar\":42}"));
        Path broken = directory.resolve("broken.png"); Files.writeString(broken, "broken"); assertThrows(java.io.IOException.class, () -> AvatarImporter.load(broken));
        Path legacy = directory.resolve("legacy.png");
        ImageIO.write(new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB), "png", legacy.toFile());
        assertEquals(48, AvatarData.decode(Base64.getEncoder().encodeToString(Files.readAllBytes(legacy))).getWidth());
        String converted = AvatarImporter.load(wrongSize); assertEquals(256, AvatarData.decode(converted).getWidth());
        assertTrue(MessageCodec.encode(Message.of(MessageType.PROFILE_OK).userId("alice").nickname("昵称").avatar(converted).build()).length() < MessageCodec.MAX_FRAME_BYTES);
    }

    @Test void detailedPhotoKeepsHighResolutionWithinTheAvatarAndFrameLimits() throws Exception {
        Path file = directory.resolve("photo.png");
        writeDetailedPhoto(file);
        assertTrue(Files.size(file) > AvatarData.MAX_BYTES);
        String avatar = AvatarImporter.load(file);
        assertTrue(Base64.getDecoder().decode(avatar).length <= AvatarData.MAX_BYTES);
        assertEquals(512, AvatarData.decode(avatar).getWidth());
        Message frame = Message.of(MessageType.DISPLAY_PROFILE).userId("a".repeat(20)).nickname("中".repeat(40)).avatar(avatar).revision(4).build();
        assertEquals(frame, MessageCodec.decode(MessageCodec.encode(frame)));
    }

    @Test void importerChecksTheNewSourceLimitsAndUsesTheCroppedShortSide() throws Exception {
        Path panorama = directory.resolve("wide.png");
        ImageIO.write(new BufferedImage(1024, 511, BufferedImage.TYPE_INT_RGB), "png", panorama.toFile());
        assertEquals(256, AvatarData.decode(AvatarImporter.load(panorama)).getWidth());
        Path tooManyPixels = directory.resolve("pixels.png");
        ImageIO.write(new BufferedImage(6000, 4001, BufferedImage.TYPE_BYTE_BINARY), "png", tooManyPixels.toFile());
        assertTrue(assertThrows(java.io.IOException.class, () -> AvatarImporter.load(tooManyPixels)).getMessage().contains("2400 万"));
        Path oversized = directory.resolve("oversized.png");
        try (var file = new java.io.RandomAccessFile(oversized.toFile(), "rw")) { file.setLength(12L * 1024 * 1024 + 1); }
        assertTrue(assertThrows(java.io.IOException.class, () -> AvatarImporter.load(oversized)).getMessage().contains("12 MiB"));
    }

    private static void writeDetailedPhoto(Path file) throws java.io.IOException {
        BufferedImage pixels = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) pixels.setRGB(x, y, random.nextInt());
        ImageIO.write(pixels, "png", file.toFile());
    }
}
