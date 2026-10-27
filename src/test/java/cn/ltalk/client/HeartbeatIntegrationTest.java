package cn.ltalk.client;

import cn.ltalk.common.*;
import cn.ltalk.server.ChatServer;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class HeartbeatIntegrationTest {
    private static final HeartbeatSettings FAST = new HeartbeatSettings(80, 500);

    @Test void idleConnectionsStayAliveWithoutExposingHeartbeatAndCanStillChat() throws Exception {
        try (ChatServer server = server()) {
            Events a = new Events(), b = new Events();
            // Different local timing demonstrates that timing is not negotiated on the wire.
            try (ChatClient first = client(a);
                 ChatClient second = new ChatClient(b, Runnable::run, 1000, 1000, new HeartbeatSettings(130, 650))) {
                first.connect("127.0.0.1", server.port(), "alice"); a.state(ChatClient.State.CONNECTED);
                second.connect("127.0.0.1", server.port(), "bob"); b.state(ChatClient.State.CONNECTED);
                TimeUnit.MILLISECONDS.sleep(1500); // Several full probe/response cycles with no user activity.
                assertEquals(2, server.onlineCount());
                assertEquals(ChatClient.State.CONNECTED, first.state());
                assertEquals(ChatClient.State.CONNECTED, second.state());
                first.sendGroup("after idle");
                assertEquals("after idle", a.message(MessageType.GROUP_MESSAGE).content());
                assertEquals("after idle", b.message(MessageType.GROUP_MESSAGE).content());
                second.sendPrivate("alice", "still private");
                assertEquals("still private", a.message(MessageType.PRIVATE_MESSAGE).content());
                assertEquals("still private", b.message(MessageType.PRIVATE_MESSAGE).content());
                assertTrue(a.messages.stream().noneMatch(HeartbeatIntegrationTest::heartbeat));
                assertTrue(b.messages.stream().noneMatch(HeartbeatIntegrationTest::heartbeat));
            }
        }
    }

    @Test void eitherDirectionCanBecomeABlackHoleAndBothEndsExpireWithoutEof() throws Exception {
        for (boolean dropUpstream : new boolean[]{true, false}) {
            try (ChatServer server = server(); BlackHole proxy = new BlackHole(server.port())) {
                Events events = new Events(), watching = new Events();
                try (ChatClient client = client(events); ChatClient watcher = client(watching)) {
                    watcher.connect("127.0.0.1", server.port(), "watcher"); watching.state(ChatClient.State.CONNECTED);
                    client.connect("127.0.0.1", proxy.port(), "alice"); events.state(ChatClient.State.CONNECTED);
                    client.sendGroup("connected"); events.message(MessageType.GROUP_MESSAGE);
                    long revision = watching.users(2).revision();
                    long started = System.nanoTime();
                    (dropUpstream ? proxy.dropUpstream : proxy.dropDownstream).set(true);
                    events.state(ChatClient.State.DISCONNECTED);
                    until(() -> server.connectionCount() == 1 && server.onlineCount() == 1);
                    Message offline = watching.users(1);
                    assertEquals(List.of("watcher"), offline.users()); assertTrue(offline.revision() > revision);
                    assertTrue(events.details.stream().anyMatch(s -> s.contains("心跳超时")));
                    assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(2500), "Detection exceeded tolerance");
                    assertThrows(IOException.class, () -> client.sendGroup("disconnected"));
                    client.connect("127.0.0.1", server.port(), "alice"); events.state(ChatClient.State.CONNECTED);
                    TimeUnit.MILLISECONDS.sleep(1000); // Past the old connection's pending deadline.
                    client.sendGroup("new connection");
                    assertEquals("new connection", events.message(MessageType.GROUP_MESSAGE).content());
                    assertEquals(2, server.onlineCount());
                    assertEquals(ChatClient.State.CONNECTED, watcher.state());
                }
            }
        }
    }

    @Test void wrongPongsAndOtherServerTrafficCannotKeepClientAlive() throws Exception {
        ExecutorService fake = Executors.newSingleThreadExecutor();
        ScheduledExecutorService traffic = Executors.newSingleThreadScheduledExecutor();
        Events events = new Events();
        try (ServerSocket endpoint = new ServerSocket(0); ChatClient client = client(events)) {
            Future<?> serving = fake.submit(() -> {
                try (Socket socket = endpoint.accept()) {
                    socket.setSoTimeout(3000);
                    MessageCodec.FrameReader reader = new MessageCodec.FrameReader(socket.getInputStream());
                    assertEquals(MessageType.LOGIN, reader.read().type());
                    MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.LOGIN_OK).userId("alice").build());
                    Message ping = reader.read(); assertEquals(MessageType.PING, ping.type());
                    ScheduledFuture<?> flooding = traffic.scheduleAtFixedRate(() -> {
                        try {
                            MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.PONG).requestId("wrong").build());
                            MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.PING).requestId("server-probe").build());
                            MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.SYSTEM).content("still sending").build());
                        } catch (IOException ex) { throw new UncheckedIOException(ex); }
                    }, 0, 40, TimeUnit.MILLISECONDS);
                    int pongs = 0;
                    try {
                        Message message;
                        while ((message = reader.read()) != null) {
                            assertEquals(MessageType.PONG, message.type()); assertEquals("server-probe", message.requestId());
                            pongs++;
                        }
                        assertTrue(pongs > 1, "Client must close while unrelated traffic continues");
                    } finally { flooding.cancel(false); }
                }
                return null;
            });
            client.connect("127.0.0.1", endpoint.getLocalPort(), "alice"); events.state(ChatClient.State.CONNECTED);
            events.state(ChatClient.State.DISCONNECTED);
            assertTrue(events.details.stream().anyMatch(s -> s.contains("心跳超时")));
            serving.get(4, TimeUnit.SECONDS);
        } finally { fake.shutdownNow(); traffic.shutdownNow(); }
    }

    @Test void serverRejectsInvalidHeartbeatAndReclaimsSilentIdentityAndSlot() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0, 1, Duration.ofSeconds(2), FAST)) {
            server.start();
            try (Peer peer = new Peer(server.port())) {
                peer.send(Message.of(MessageType.PING).requestId("pre-login").build()); peer.error("NOT_LOGGED_IN");
                peer.send(Message.of(MessageType.LOGIN).requestId("login").userId("alice").build());
                assertEquals(MessageType.LOGIN_OK, peer.read().type());
                for (String id : new String[]{null, " ", "x".repeat(65)}) {
                    for (MessageType type : new MessageType[]{MessageType.PING, MessageType.PONG}) {
                        peer.send(Message.of(type).requestId(id).build()); peer.error("BAD_MESSAGE");
                    }
                }
                peer.send(Message.of(MessageType.PING).requestId("client-probe").build());
                assertEquals("client-probe", peer.type(MessageType.PONG).requestId());
                // Bypass Peer's normal PING responder: unrelated chat and stale PONG do not satisfy a probe.
                Message ping;
                do { ping = peer.reader.read(); assertNotNull(ping); } while (ping.type() != MessageType.PING);
                peer.send(Message.of(MessageType.PONG).requestId(ping.requestId() + "-stale").build());
                peer.group("not a heartbeat response");
                while (peer.reader.read() != null) { }
                until(() -> server.onlineCount() == 0 && server.connectionCount() == 0);
                try (Peer reclaimed = Peer.login(server.port(), "alice")) {
                    reclaimed.group("slot and name released"); reclaimed.type(MessageType.GROUP_MESSAGE);
                }
            }
        }
    }

    @Test void incompletePongCannotExtendDeadlineOrBlockWatchdog() throws Exception {
        try (ChatServer server = server(); Peer peer = Peer.login(server.port(), "alice")) {
            Message ping;
            do { ping = peer.reader.read(); assertNotNull(ping); } while (ping.type() != MessageType.PING);
            peer.raw("{\"type\":\"PONG\",\"requestId\":\"");
            until(() -> server.onlineCount() == 0 && server.connectionCount() == 0);
            assertNull(peer.reader.read());
        }
    }

    @Test void repeatedHeartbeatConnectionsLeaveNoWatchdogOrWriterThreads() throws Exception {
        try (ChatServer server = server()) {
            Events events = new Events();
            try (ChatClient client = client(events)) {
                for (int round = 0; round < 5; round++) {
                    client.connect("127.0.0.1", server.port(), "alice"); events.state(ChatClient.State.CONNECTED);
                    TimeUnit.MILLISECONDS.sleep(180);
                    client.disconnect(); events.state(ChatClient.State.DISCONNECTED);
                    until(() -> server.connectionCount() == 0);
                }
            }
        }
        until(() -> Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && t.getName().startsWith("ltalk-heartbeat-")));
    }

    private static ChatServer server() throws Exception {
        ChatServer server = new ChatServer("127.0.0.1", 0, 2, Duration.ofSeconds(2), FAST);
        server.start(); return server;
    }

    private static ChatClient client(Events events) { return new ChatClient(events, Runnable::run, 1000, 1000, FAST); }
    private static boolean heartbeat(Message message) { return message.type() == MessageType.PING || message.type() == MessageType.PONG; }
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not become true");
    }

    private static final class Events implements ClientEventListener {
        final BlockingQueue<ChatClient.State> states = new LinkedBlockingQueue<>();
        final List<String> details = new CopyOnWriteArrayList<>();
        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        @Override public void onStateChanged(ChatClient.State state, String detail) { details.add(detail); states.add(state); }
        @Override public void onGroup(Message message) { messages.add(message); }
        @Override public void onPrivate(Message message) { messages.add(message); }
        @Override public void onUserList(Message message) { messages.add(message); }
        @Override public void onSystem(Message message) { messages.add(message); }
        @Override public void onError(Message message) { messages.add(message); }
        void state(ChatClient.State expected) throws Exception {
            ChatClient.State actual;
            do { actual = states.poll(4, TimeUnit.SECONDS); assertNotNull(actual, "Missing state " + expected); } while (actual != expected);
        }
        Message message(MessageType type) throws Exception {
            Message actual;
            do { actual = messages.poll(4, TimeUnit.SECONDS); assertNotNull(actual, "Missing message " + type); } while (actual.type() != type);
            return actual;
        }
        Message users(int count) throws Exception {
            Message list;
            do { list = message(MessageType.USER_LIST); } while (list.users().size() != count);
            return list;
        }
    }

    /** Drops bytes while keeping both TCP legs open, without forwarding FIN/RST to the other leg. */
    private static final class BlackHole implements AutoCloseable {
        final AtomicBoolean dropUpstream = new AtomicBoolean(), dropDownstream = new AtomicBoolean();
        final ServerSocket listener;
        final ExecutorService pumps = Executors.newFixedThreadPool(3);
        volatile Socket downstream, upstream;
        final Future<?> serving;
        BlackHole(int serverPort) throws IOException {
            listener = new ServerSocket(0);
            serving = pumps.submit(() -> {
                downstream = listener.accept(); upstream = new Socket("127.0.0.1", serverPort);
                pumps.submit(() -> { pump(downstream, upstream, dropUpstream); return null; });
                pump(upstream, downstream, dropDownstream);
                return null;
            });
        }
        int port() { return listener.getLocalPort(); }
        private void pump(Socket source, Socket target, AtomicBoolean drop) throws IOException {
            byte[] bytes = new byte[4096]; int length;
            while ((length = source.getInputStream().read(bytes)) != -1)
                if (!drop.get()) { target.getOutputStream().write(bytes, 0, length); target.getOutputStream().flush(); }
        }
        @Override public void close() throws Exception {
            listener.close();
            if (downstream != null) downstream.close();
            if (upstream != null) upstream.close();
            pumps.shutdownNow(); assertTrue(pumps.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
