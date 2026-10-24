package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class ChatServerTest {
    private ChatServer server;
    @BeforeEach void start() throws Exception { server = new ChatServer("127.0.0.1", 0); server.start(); }
    @AfterEach void stop() { server.close(); }

    @Test void groupEchoPrivateIsolationAndNoHistory() throws Exception {
        try (Peer a = Peer.login(server.port(), " alice "); Peer b = Peer.login(server.port(), "bob"); Peer c = Peer.login(server.port(), "carol")) {
            for (Peer peer : List.of(a, b, c)) assertEquals(List.of("alice", "bob", "carol"), peer.users(3).users());
            String content = "G-001 中文🙂\n下一行 <html>";
            a.group(content);
            for (Peer peer : List.of(a, b, c)) {
                Message event = peer.type(MessageType.GROUP_MESSAGE);
                assertEquals(content, event.content()); assertEquals("alice", event.from()); assertNotNull(event.serverTime());
            }
            a.privateTo("bob", "P-001");
            for (Peer peer : List.of(a, b)) {
                Message event = peer.type(MessageType.PRIVATE_MESSAGE);
                assertEquals("alice", event.from()); assertEquals("bob", event.to()); assertEquals("P-001", event.content());
            }
            b.privateTo("alice", "P-002");
            a.type(MessageType.PRIVATE_MESSAGE); b.type(MessageType.PRIVATE_MESSAGE);
            c.assertNo(m -> m.type() == MessageType.PRIVATE_MESSAGE || "P-001".equals(m.content()), 200);
            for (Peer peer : List.of(a, b, c)) peer.assertNo(m -> m.type() == MessageType.GROUP_MESSAGE, 70);
            try (Peer late = Peer.login(server.port(), "dave")) {
                late.users(4);
                late.assertNo(m -> m.type() == MessageType.GROUP_MESSAGE || m.type() == MessageType.PRIVATE_MESSAGE, 100);
            }
        }
    }

    @Test void duplicateLoginPreservesOriginalAndRejectsNewConnection() throws Exception {
        try (Peer original = Peer.login(server.port(), "alice"); Peer duplicate = new Peer(server.port())) {
            duplicate.send(Message.of(MessageType.LOGIN).requestId("duplicate").userId("alice").build());
            duplicate.error("USER_EXISTS"); assertNull(duplicate.read());
            original.group("still here"); assertEquals("still here", original.type(MessageType.GROUP_MESSAGE).content());
            assertEquals(1, server.onlineCount());
        }
    }

    @Test void concurrentSameNameHasOnlyOneWinnerAndLoginOkIsFirst() throws Exception {
        try (Peer a = new Peer(server.port()); Peer b = new Peer(server.port()); Peer watcher = Peer.login(server.port(), "watcher")) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Message>> results = new ArrayList<>();
                for (Peer peer : List.of(a, b)) results.add(pool.submit(() -> {
                    go.await(); peer.send(Message.of(MessageType.LOGIN).requestId("claim").userId("same").build()); return peer.read();
                }));
                go.countDown();
                List<MessageType> types = List.of(results.get(0).get().type(), results.get(1).get().type());
                assertEquals(1, Collections.frequency(types, MessageType.LOGIN_OK));
                assertEquals(1, Collections.frequency(types, MessageType.ERROR));
                assertEquals(List.of("same", "watcher"), watcher.users(2).users());
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void badRequestsDoNotBroadcastAndIdentityCannotBeForged() throws Exception {
        try (Peer raw = new Peer(server.port())) {
            raw.group("not logged in"); raw.error("NOT_LOGGED_IN");
            raw.raw("garbage\n"); raw.error("BAD_MESSAGE");
            raw.raw("{\"type\":\"WHAT\"}\n"); raw.error("BAD_MESSAGE");
            raw.raw("{\"type\":\"LOGIN\",\"requestId\":\"r\",\"userId\":\"bad-id\"}\n");
            raw.error("INVALID_USER_ID"); assertNull(raw.read());
        }
        try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob")) {
            a.send(Message.of(MessageType.LOGIN).requestId("again").userId("other").build()); a.error("ALREADY_LOGGED_IN");
            a.raw("{\"type\":\"GROUP_SEND\",\"requestId\":\"wrong\",\"content\":4}\n");
            Message badField = a.type(MessageType.ERROR);
            assertEquals("BAD_MESSAGE", badField.code()); assertEquals("wrong", badField.requestId());
            a.raw("{\"type\":\"GROUP_SEND\",\"requestId\":\"missing\"}\n"); a.error("BAD_MESSAGE");
            a.raw("{\"type\":\"GROUP_SEND\",\"content\":\"no request id\"}\n"); a.error("BAD_MESSAGE");
            a.send(Message.of(MessageType.GROUP_SEND).requestId("x".repeat(15000)).content("oversized id").build()); a.error("BAD_MESSAGE");
            a.raw("{\"type\":\"GROUP_SEND\",\"requestId\":\"forged\",\"from\":\"bob\",\"serverTime\":\"fake\",\"content\":\"valid\"}\n");
            for (Peer peer : List.of(a, b)) {
                Message event = peer.type(MessageType.GROUP_MESSAGE);
                assertEquals("alice", event.from()); assertNotEquals("fake", event.serverTime()); assertEquals("forged", event.requestId());
            }
            b.assertNo(m -> m.type() == MessageType.GROUP_MESSAGE, 100);
        }
    }

    @Test void privateErrorsAndTextBoundaries() throws Exception {
        try (Peer a = Peer.login(server.port(), "alice")) {
            a.privateTo("ghost", "hello"); a.error("USER_OFFLINE");
            a.privateTo("alice", "hello"); a.error("SELF_PRIVATE_NOT_ALLOWED");
            a.privateTo(null, "hello"); a.error("BAD_MESSAGE");
            a.group(" \t\n"); a.error("EMPTY_CONTENT");
            a.group("x".repeat(2001)); a.error("CONTENT_TOO_LONG");
            a.group("中".repeat(2000)); assertEquals(2000, a.type(MessageType.GROUP_MESSAGE).content().length());
            a.group("🙂".repeat(1000)); assertEquals(2000, a.type(MessageType.GROUP_MESSAGE).content().length());
        }
    }

    @Test void realSocketFragmentationAndCoalescing() throws Exception {
        try (Peer a = new Peer(server.port())) {
            a.raw("{\"type\":\"LOG");
            a.raw("IN\",\"requestId\":\"r1\",\"userId\":\"alice\"}\n");
            assertEquals(MessageType.LOGIN_OK, a.read().type());
            a.raw("{\"type\":\"GROUP_SEND\",\"requestId\":\"r2\",\"content\":\"一\\n二\"}\n"
                    + "{\"type\":\"GROUP_SEND\",\"requestId\":\"r3\",\"content\":\"三\"}\n");
            assertEquals("一\n二", a.type(MessageType.GROUP_MESSAGE).content());
            assertEquals("三", a.type(MessageType.GROUP_MESSAGE).content());
        }
    }

    @Test void oversizedUnterminatedFrameClosesOnlyOffendingConnection() throws Exception {
        try (Peer good = Peer.login(server.port(), "alice"); Peer bad = new Peer(server.port())) {
            bad.raw("x".repeat(MessageCodec.MAX_FRAME_BYTES + 1));
            bad.error("FRAME_TOO_LARGE"); assertNull(bad.read());
            good.group("healthy"); assertEquals("healthy", good.type(MessageType.GROUP_MESSAGE).content());
        }
    }

    @Test void loginTimeoutIsAbsoluteAndConnectionSlotsAreReclaimed() throws Exception {
        server.close(); server = new ChatServer("127.0.0.1", 0, 1, Duration.ofMillis(250)); server.start();
        try (Peer idle = new Peer(server.port())) { assertNull(idle.read()); }
        try (Peer ready = Peer.login(server.port(), "alice"); Peer extra = new Peer(server.port())) {
            assertNull(extra.read()); ready.group("slot reclaimed"); ready.type(MessageType.GROUP_MESSAGE);
        }
    }

    @Test void tricklingBytesCannotExtendLoginDeadline() throws Exception {
        server.close(); server = new ChatServer("127.0.0.1", 0, 2, Duration.ofMillis(300)); server.start();
        ScheduledExecutorService trickle = Executors.newSingleThreadScheduledExecutor();
        try (Peer peer = new Peer(server.port())) {
            long started = System.nanoTime();
            peer.raw("{");
            trickle.scheduleAtFixedRate(() -> { try { peer.raw(" "); } catch (IOException ignored) { } }, 30, 30, TimeUnit.MILLISECONDS);
            assertNull(peer.read());
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
        } finally { trickle.shutdownNow(); }
    }

    @Test void logoutAbruptCloseAndTenReconnectsKeepMembershipConsistent() throws Exception {
        try (Peer watcher = Peer.login(server.port(), "watcher")) {
            watcher.users(1);
            long revision = -1;
            for (int i = 0; i < 10; i++) {
                try (Peer a = Peer.login(server.port(), "alice")) {
                    Message joined = watcher.users(2); assertTrue(joined.revision() > revision);
                    revision = joined.revision();
                    if (i % 2 == 0) a.send(Message.of(MessageType.LOGOUT).build());
                    else a.socket.setSoLinger(true, 0); // RST emulates an abrupt process exit.
                }
                Message left = watcher.users(1); assertTrue(left.revision() > revision); revision = left.revision();
                assertEquals(List.of("watcher"), left.users());
            }
            assertEquals(1, server.onlineCount());
        }
    }

    @Test void concurrentSendsPreserveFramesAndRecipientSets() throws Exception {
        try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob"); Peer c = Peer.login(server.port(), "carol")) {
            List<Peer> peers = List.of(a, b, c);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            Set<String> expected = new HashSet<>();
            for (int sender = 0; sender < 3; sender++) for (int n = 0; n < 20; n++) expected.add(sender + ":" + n);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int index = 0; index < 3; index++) {
                    int sender = index;
                    futures.add(pool.submit(() -> { for (int n = 0; n < 20; n++) peers.get(sender).group(sender + ":" + n); return null; }));
                }
                for (Future<?> future : futures) future.get();
                for (Peer peer : peers) {
                    Set<String> actual = new HashSet<>();
                    for (int n = 0; n < 60; n++) assertTrue(actual.add(peer.type(MessageType.GROUP_MESSAGE).content()), "duplicate echo");
                    assertEquals(expected, actual);
                }
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void occupiedPortAndShutdownAreObservable() throws Exception {
        try (ChatServer conflict = new ChatServer("127.0.0.1", server.port())) { assertThrows(BindException.class, conflict::start); }
        try (Peer a = Peer.login(server.port(), "alice")) {
            a.users(1); server.close();
            while (a.read() != null) { /* drain final system event before EOF */ }
            assertEquals(0, server.connectionCount()); assertEquals(0, server.onlineCount());
        }
    }
}
