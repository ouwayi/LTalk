package cn.ltalk.client;

import cn.ltalk.common.*;
import cn.ltalk.server.ChatServer;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class AccountFlowTest {
    @Test void connectRegisterAutoLoginProfileAndRetryKeepIdentityBoundToSocket() throws Exception {
        Events events = new Events();
        try (ChatServer server = new ChatServer("127.0.0.1", 0, 4, Duration.ofMillis(300), new HeartbeatSettings(80, 1200))) {
            server.start();
            try (ChatClient client = new ChatClient(events, Runnable::run, 2000, 4000, new HeartbeatSettings(100, 1200))) {
                client.connectServer("127.0.0.1", server.port()); events.state(ChatClient.State.SERVER_CONNECTED);
                assertEquals(0, server.onlineCount()); assertThrows(java.io.IOException.class, () -> client.sendGroup("blocked"));
                Thread.sleep(450); assertEquals(ChatClient.State.SERVER_CONNECTED, client.state());
                client.login("unknown", "wrong!12"); assertEquals("LOGIN_FAILED", events.errors.poll(5, TimeUnit.SECONDS).code());
                assertEquals(ChatClient.State.SERVER_CONNECTED, client.state());
                client.registerAndLogin(null, "昵称🙂", "pass!12", true); events.state(ChatClient.State.CONNECTED);
                UserProfile profile = events.profiles.poll(5, TimeUnit.SECONDS); assertNotNull(profile); assertEquals("昵称🙂", profile.nickname());
                assertTrue(profile.userId().startsWith("u_")); assertEquals(profile.userId(), events.registered.poll(3, TimeUnit.SECONDS));
                client.updateProfile("修改昵称", ""); UserProfile updated = events.profiles.poll(4, TimeUnit.SECONDS); assertNotNull(updated); assertEquals("修改昵称", updated.nickname());
                assertEquals(profile.userId(), updated.userId()); assertEquals(profile.createdAt(), updated.createdAt());
                client.updateProfile(" ", ""); Message failure = events.errors.poll(4, TimeUnit.SECONDS); assertNotNull(failure); assertEquals("MISSING_FIELD", failure.code());
                client.refreshProfile(); assertEquals("修改昵称", events.profiles.poll(4, TimeUnit.SECONDS).nickname());
                client.disconnect(); events.state(ChatClient.State.DISCONNECTED);
                client.connectServer("127.0.0.1", server.port()); events.state(ChatClient.State.SERVER_CONNECTED);
                assertThrows(java.io.IOException.class, () -> client.updateProfile("cannot save", ""));
                client.login(profile.userId(), "pass!12"); events.state(ChatClient.State.CONNECTED);
                assertEquals("修改昵称", events.profiles.poll(5, TimeUnit.SECONDS).nickname());
            }
        }
    }
    @Test void registrationFailureDoesNotAutoLoginAndSameConnectionCanRetry() throws Exception {
        Events events = new Events();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); Peer.seed(server);
            try (ChatClient client = new ChatClient(events, Runnable::run)) {
                client.connectServer("127.0.0.1", server.port()); events.state(ChatClient.State.SERVER_CONNECTED);
                client.registerAndLogin("alice", "重复", "pass!12", false);
                assertEquals("USER_EXISTS", events.errors.poll(5, TimeUnit.SECONDS).code()); assertEquals(0, server.onlineCount());
                assertTrue(events.profiles.isEmpty()); assertTrue(events.registered.isEmpty());
                client.registerAndLogin("new_account", "新用户", "pass!12", false); events.state(ChatClient.State.CONNECTED);
                assertEquals("new_account", events.profiles.poll(5, TimeUnit.SECONDS).userId());
            }
        }
    }
    @Test void silentHandshakeAndAuthenticationTimeoutCloseTransport() throws Exception {
        for (boolean handshake : new boolean[]{true, false}) {
            Events events = new Events(); ExecutorService fake = Executors.newSingleThreadExecutor();
            try (ServerSocket server = new ServerSocket(0); ChatClient client = new ChatClient(events, Runnable::run, 150, 250)) {
                server.setSoTimeout(3000);
                Future<?> serving = fake.submit(() -> {
                    try (Socket socket = server.accept()) {
                        var reader = new MessageCodec.FrameReader(socket.getInputStream()); Message hello = reader.read();
                        if (!handshake) {
                            MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.HELLO_OK).requestId(hello.requestId()).build());
                            assertEquals(MessageType.LOGIN, reader.read().type());
                        }
                        assertNull(reader.read());
                    } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
                });
                client.connectServer("127.0.0.1", server.getLocalPort());
                if (!handshake) { events.state(ChatClient.State.SERVER_CONNECTED); client.login("alice", "pass!12"); }
                events.state(ChatClient.State.DISCONNECTED); serving.get(4, TimeUnit.SECONDS);
                assertThrows(java.io.IOException.class, () -> client.sendGroup("closed"));
            } finally { fake.shutdownNow(); }
        }
    }
    @Test void profileTimeoutIgnoresLateReplyAndKeepsChatUsable() throws Exception {
        Events events = new Events(); ExecutorService fake = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0); ChatClient client = new ChatClient(events, Runnable::run, 200, 250)) {
            Future<?> serving = fake.submit(() -> {
                try (Socket socket = server.accept()) {
                    var reader = new MessageCodec.FrameReader(socket.getInputStream()); reader.read();
                    MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.LOGIN_OK).userId("alice").nickname("original").avatar("").build());
                    Message request = reader.read(); assertEquals(MessageType.PROFILE_UPDATE, request.type());
                    Thread.sleep(450);
                    MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.PROFILE_OK).requestId(request.requestId()).userId("alice").nickname("late").avatar("").build());
                    Message chat = reader.read(); assertEquals(MessageType.GROUP_SEND, chat.type());
                    MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.GROUP_MESSAGE).from("alice").content(chat.content()).serverTime(java.time.Instant.now().toString()).build());
                    reader.read();
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            client.connect("127.0.0.1", server.getLocalPort(), "alice", "pass!12"); events.state(ChatClient.State.CONNECTED);
            assertEquals("original", events.profiles.poll(3, TimeUnit.SECONDS).nickname());
            client.updateProfile("late", ""); Message failure = events.errors.poll(3, TimeUnit.SECONDS); assertNotNull(failure); assertEquals("PROFILE_FAILED", failure.code());
            assertEquals(ChatClient.State.CONNECTED, client.state()); client.sendGroup("still usable");
            assertEquals("still usable", events.groups.poll(3, TimeUnit.SECONDS).content()); assertTrue(events.profiles.isEmpty());
            client.disconnect(); serving.get(3, TimeUnit.SECONDS);
        } finally { fake.shutdownNow(); }
    }
    private static final class Events implements ClientEventListener {
        final BlockingQueue<ChatClient.State> states = new LinkedBlockingQueue<>();
        final BlockingQueue<Message> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<UserProfile> profiles = new LinkedBlockingQueue<>();
        final BlockingQueue<String> registered = new LinkedBlockingQueue<>();
        final BlockingQueue<Message> groups = new LinkedBlockingQueue<>();
        public void onStateChanged(ChatClient.State s, String detail) { states.add(s); }
        public void onError(Message message) { errors.add(message); }
        public void onProfile(UserProfile profile) { profiles.add(profile); }
        public void onRegistered(String id) { registered.add(id); }
        public void onGroup(Message message) { groups.add(message); }
        void state(ChatClient.State expected) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() < end) {
                ChatClient.State s = states.poll(100, TimeUnit.MILLISECONDS); if (s == expected) return;
            }
            fail("Did not reach " + expected);
        }
    }
}
