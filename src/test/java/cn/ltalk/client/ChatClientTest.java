package cn.ltalk.client;

import cn.ltalk.common.*;
import cn.ltalk.server.ChatServer;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ChatClientTest {
    private ChatServer server;
    @BeforeEach void start() throws Exception { server = new ChatServer("127.0.0.1", 0); server.start(); }
    @AfterEach void stop() { server.close(); }

    @Test void realClientSendsReceivesAndReconnectsAfterServerStops() throws Exception {
        Events events = new Events();
        try (ChatClient client = new ChatClient(events, Runnable::run); Peer bob = Peer.login(server.port(), "bob")) {
            assertThrows(IOException.class, () -> client.sendGroup("not connected"));
            client.connect("127.0.0.1", server.port(), " alice "); events.state(ChatClient.State.CONNECTED);
            Message users = events.message(MessageType.USER_LIST); assertTrue(users.users().contains("alice"));
            client.sendGroup("中文🙂\nnewline");
            assertEquals("中文🙂\nnewline", events.message(MessageType.GROUP_MESSAGE).content());
            assertEquals("alice", bob.type(MessageType.GROUP_MESSAGE).from());
            client.sendPrivate("bob", "secret");
            assertEquals("secret", events.message(MessageType.PRIVATE_MESSAGE).content());
            assertEquals("secret", bob.type(MessageType.PRIVATE_MESSAGE).content());
            assertThrows(IOException.class, () -> client.sendGroup(" "));
            assertThrows(IOException.class, () -> client.sendGroup("x".repeat(2001)));
            assertThrows(IOException.class, () -> client.sendPrivate("alice", "self"));
            client.sendPrivate("ghost", "offline"); assertEquals("USER_OFFLINE", events.message(MessageType.ERROR).code());
            assertEquals(ChatClient.State.CONNECTED, client.state());
            server.close(); events.state(ChatClient.State.DISCONNECTED);
            assertThrows(IOException.class, () -> client.sendGroup("after shutdown"));
            server = new ChatServer("127.0.0.1", 0); server.start();
            client.connect("127.0.0.1", server.port(), "alice"); events.state(ChatClient.State.CONNECTED);
            client.sendGroup("reconnected"); assertEquals("reconnected", events.message(MessageType.GROUP_MESSAGE).content());
        }
    }

    @Test void duplicateLoginSurfacesReasonAndAllowsRetry() throws Exception {
        Events events = new Events();
        try (Peer alice = Peer.login(server.port(), "alice"); ChatClient client = new ChatClient(events, Runnable::run)) {
            client.connect("127.0.0.1", server.port(), "alice");
            assertEquals("USER_EXISTS", events.message(MessageType.ERROR).code()); events.state(ChatClient.State.DISCONNECTED);
            client.connect("127.0.0.1", server.port(), "other"); events.state(ChatClient.State.CONNECTED);
            client.disconnect(); events.state(ChatClient.State.DISCONNECTED);
        }
    }

    @Test void refusedConnectionAndSilentLoginReturnToDisconnected() throws Exception {
        Events events = new Events();
        int unused;
        try (ServerSocket temporary = new ServerSocket(0)) { unused = temporary.getLocalPort(); }
        try (ChatClient client = new ChatClient(events, Runnable::run, 200, 200)) {
            assertThrows(IllegalArgumentException.class, () -> client.connect("", 9000, "alice"));
            assertThrows(IllegalArgumentException.class, () -> client.connect("localhost", 0, "alice"));
            client.connect("127.0.0.1", unused, "alice"); events.state(ChatClient.State.DISCONNECTED);
            try (ServerSocket silent = new ServerSocket(0)) {
                silent.setSoTimeout(4000);
                client.connect("127.0.0.1", silent.getLocalPort(), "alice");
                try (Socket accepted = silent.accept()) { events.state(ChatClient.State.DISCONNECTED); }
            }
            assertEquals(ChatClient.State.DISCONNECTED, client.state());
        }
    }

    @Test void userListVersionsIgnoreStaleSnapshotsAndResetOnReconnect() throws Exception {
        Events events = new Events();
        ExecutorService fake = Executors.newSingleThreadExecutor();
        try (ServerSocket endpoint = new ServerSocket(0); ChatClient client = new ChatClient(events, Runnable::run)) {
            endpoint.setSoTimeout(4000);
            Future<?> serving = fake.submit(() -> {
                for (int round = 0; round < 2; round++) {
                    try (Socket socket = endpoint.accept()) {
                        new MessageCodec.FrameReader(socket.getInputStream()).read();
                        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.LOGIN_OK).userId("alice").build());
                        long revision = round == 0 ? 5 : 1;
                        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.USER_LIST).users(List.of("alice", "bob")).revision(revision).build());
                        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.USER_LIST).users(List.of("stale")).revision(revision - 1).build());
                        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.SYSTEM).content("barrier").build());
                        socket.getInputStream().read();
                    }
                }
                return null;
            });
            for (long expected : new long[]{5, 1}) {
                client.connect("127.0.0.1", endpoint.getLocalPort(), "alice"); events.state(ChatClient.State.CONNECTED);
                Message list = events.message(MessageType.USER_LIST); assertEquals(expected, list.revision()); assertTrue(list.users().contains("bob"));
                events.message(MessageType.SYSTEM);
                assertTrue(events.lists.stream().noneMatch(m -> m.users().contains("stale")));
                client.disconnect(); events.state(ChatClient.State.DISCONNECTED);
            }
            serving.get(3, TimeUnit.SECONDS);
        } finally { fake.shutdownNow(); }
    }

    @Test void callbacksQueuedFromOldConnectionCannotOverwriteNewConnection() throws Exception {
        Queue<Runnable> edt = new ConcurrentLinkedQueue<>();
        Events events = new Events();
        try (ChatClient client = new ChatClient(events, edt::add)) {
            client.connect("127.0.0.1", server.port(), "old_user");
            until(() -> client.state() == ChatClient.State.CONNECTED && server.onlineCount() == 1);
            client.disconnect();
            client.connect("127.0.0.1", server.port(), "new_user");
            until(() -> client.state() == ChatClient.State.CONNECTED && server.onlineCount() == 1);
            client.sendGroup("barrier");
            until(() -> edt.size() >= 7);
            Runnable task;
            while ((task = edt.poll()) != null) task.run();
            assertTrue(events.details.stream().noneMatch(s -> s.contains("old_user")));
            assertTrue(events.details.stream().anyMatch(s -> s.contains("new_user")));
            assertEquals(ChatClient.State.CONNECTED, client.state());
        }
    }

    @Test void repeatedConnectionsReleaseClientAndServerResources() throws Exception {
        Events events = new Events();
        try (ChatClient client = new ChatClient(events, Runnable::run)) {
            for (int i = 0; i < 10; i++) {
                client.connect("127.0.0.1", server.port(), "alice"); events.state(ChatClient.State.CONNECTED);
                client.disconnect(); events.state(ChatClient.State.DISCONNECTED);
                until(() -> server.connectionCount() == 0);
            }
        }
        until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("ltalk-client-")));
    }

    static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }

    private static final class Events implements ClientEventListener {
        final BlockingQueue<ChatClient.State> states = new LinkedBlockingQueue<>();
        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        final List<String> details = new CopyOnWriteArrayList<>();
        final List<Message> lists = new CopyOnWriteArrayList<>();
        @Override public void onStateChanged(ChatClient.State state, String detail) { details.add(detail); states.add(state); }
        @Override public void onGroup(Message message) { messages.add(message); }
        @Override public void onPrivate(Message message) { messages.add(message); }
        @Override public void onUserList(Message message) { lists.add(message); messages.add(message); }
        @Override public void onSystem(Message message) { messages.add(message); }
        @Override public void onError(Message message) { messages.add(message); }
        void state(ChatClient.State expected) throws Exception {
            ChatClient.State actual;
            do { actual = states.poll(5, TimeUnit.SECONDS); assertNotNull(actual, "Missing state " + expected); } while (actual != expected);
        }
        Message message(MessageType expected) throws Exception {
            Message actual;
            do { actual = messages.poll(5, TimeUnit.SECONDS); assertNotNull(actual, "Missing message " + expected); } while (actual.type() != expected);
            return actual;
        }
    }
}
