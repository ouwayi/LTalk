package cn.ltalk.server;

import cn.ltalk.client.*;
import cn.ltalk.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(35)
class PublicConnectionTest {
    @TempDir Path directory;

    @Test void tlsRequiresTrustAndExactHostBeforeApplicationAuthentication() throws Exception {
        TlsTestIdentity identity = new TlsTestIdentity(directory);
        try (ChatServer server = server(identity, 6, 4, 10, 10)) {
            server.accounts().register("alice", "Alice", "test123!", false);
            Events verified = new Events();
            try (ChatClient client = client(verified, identity.client)) {
                client.connectServer("localhost", server.port(), true);
                verified.await(ChatClient.State.SERVER_CONNECTED);
                client.login("alice", "test123!");
                verified.await(ChatClient.State.CONNECTED);
                client.sendGroup("TLS 群聊");
                assertEquals("TLS 群聊", verified.messages.poll(4, TimeUnit.SECONDS).content());
            }

            Events wrongName = new Events(), untrusted = new Events();
            try (ChatClient client = client(wrongName, identity.client); ChatClient foreign = client(untrusted, SSLContext.getDefault())) {
                client.connectServer("127.0.0.1", server.port(), true);
                assertTrue(wrongName.await(ChatClient.State.DISCONNECTED).contains("TLS 连接失败"));
                foreign.connectServer("localhost", server.port(), true);
                assertTrue(untrusted.await(ChatClient.State.DISCONNECTED).contains("TLS 连接失败"));
            }
            try (Socket plain = new Socket("127.0.0.1", server.port())) {
                plain.setSoTimeout(3000);
                MessageCodec.write(plain.getOutputStream(), Message.of(MessageType.HELLO).requestId("plain").build());
                // A TLS alert or connection close is valid, an application response is not.
                try { assertNotEquals('{', plain.getInputStream().read()); }
                catch (SocketException closed) { assertTrue(plain.isConnected()); }
            }
        }
    }

    @Test void capsAnonymousConnectionsReclaimsSlotsAndLimitsAccountRequests() throws Exception {
        TlsTestIdentity identity = new TlsTestIdentity(directory);
        try (ChatServer server = server(identity, 1, 1, 1, 1)) {
            try (Socket raw = new Socket("127.0.0.1", server.port())) {
                try (SSLSocket tls = TlsTransport.layer(raw, identity.client, "localhost", true)) {
                    tls.setSoTimeout(4000);
                    tls.startHandshake();
                    MessageCodec.write(tls.getOutputStream(), Message.of(MessageType.HELLO).requestId("hello").build());
                    var reader = new MessageCodec.FrameReader(tls.getInputStream());
                    assertEquals(MessageType.HELLO_OK, reader.read().type());
                    try (Socket extra = new Socket("127.0.0.1", server.port())) {
                        extra.setSoTimeout(2000);
                        assertEquals(-1, extra.getInputStream().read());
                    }
                    try { assertNull(reader.read()); }
                    catch (SSLException closed) { assertTrue(tls.isConnected()); }
                }
            }
            long stop = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (server.connectionCount() != 0 && System.nanoTime() < stop) Thread.sleep(10);
            assertEquals(0, server.connectionCount());
            assertTrue(server.admissionRejectedCount() >= 1);
        }
        try (ChatServer limited = server(identity, 6, 1, 10, 1)) {
            limited.accounts().register("alice", "Alice", "test123!", false);
            Events events = new Events();
            try (ChatClient client = client(events, identity.client)) {
                client.connectServer("localhost", limited.port(), true);
                events.await(ChatClient.State.SERVER_CONNECTED);
                client.login("alice", "wrong123!");
                assertEquals("LOGIN_FAILED", events.errors.poll(5, TimeUnit.SECONDS).code());
                client.login("alice", "test123!");
                assertEquals("RATE_LIMITED", events.errors.poll(5, TimeUnit.SECONDS).code());
                client.registerAndLogin("first", "First", "test123!", false);
                events.await(ChatClient.State.CONNECTED);
            }
            Events another = new Events();
            try (ChatClient client = client(another, identity.client)) {
                client.connectServer("localhost", limited.port(), true);
                another.await(ChatClient.State.SERVER_CONNECTED);
                client.registerAndLogin("second", "Second", "test123!", false);
                assertEquals("RATE_LIMITED", another.errors.poll(5, TimeUnit.SECONDS).code());
            }
        }
    }

    @Test void backupRestoresTlsLoginSharedHistoryPrivateHistoryAndPersonalDeletion() throws Exception {
        TlsTestIdentity identity = new TlsTestIdentity(directory);
        Path live = directory.resolve("live"), restored = directory.resolve("restore");
        Events a = new Events(), b = new Events();
        try (ChatServer server = backupServer(identity, live);
             ChatClient alice = client(a, identity.client); ChatClient bob = client(b, identity.client)) {
            server.accounts().register("alice", "Alice", "test123!", false);
            server.accounts().register("bob", "Bob", "test123!", false);
            login(alice, a, server.port(), "alice");
            login(bob, b, server.port(), "bob");
            long ready = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (server.onlineCount() != 2 && System.nanoTime() < ready) Thread.sleep(10);
            assertEquals(2, server.onlineCount());
            alice.sendGroup("备份公共消息");
            Message group = b.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(group);
            alice.sendPrivate("bob", "备份私聊");
            Message privateMessage = b.messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(privateMessage);
            assertEquals("备份私聊", privateMessage.content());
            alice.deleteHistory(ConversationScope.PUBLIC, group.messageId());
            assertEquals(group.messageId(), a.deletions.poll(5, TimeUnit.SECONDS));
        }
        try (var files = Files.walk(live)) {
            for (Path source : files.toList()) {
                Path destination = restored.resolve(live.relativize(source));
                if (Files.isDirectory(source)) Files.createDirectory(destination);
                else if (!source.getFileName().toString().equals("server.lock")) Files.copy(source, destination);
            }
        }
        Events afterA = new Events(), afterB = new Events();
        try (ChatServer server = backupServer(identity, restored);
             ChatClient alice = client(afterA, identity.client); ChatClient bob = client(afterB, identity.client)) {
            login(alice, afterA, server.port(), "alice");
            login(bob, afterB, server.port(), "bob");
            alice.loadHistory(); bob.loadHistory();
            assertNotNull(afterA.completed.poll(5, TimeUnit.SECONDS));
            assertNotNull(afterB.completed.poll(5, TimeUnit.SECONDS));
            assertEquals(java.util.List.of("备份私聊"), afterA.history.stream().map(ChatRecord::content).toList());
            assertEquals(java.util.List.of("备份公共消息", "备份私聊"), afterB.history.stream().map(ChatRecord::content).toList());
        }
    }

    private static ChatServer backupServer(TlsTestIdentity identity, Path data) throws IOException {
        ChatServer server = new ChatServer("127.0.0.1", 0, 12, Duration.ofSeconds(5));
        server.publicAccess(new PublicAccess(identity.server, 4, 20, 20, 4, 8, 2, 10));
        server.dataDirectory(data);
        server.start();
        return server;
    }

    private static void login(ChatClient client, Events events, int port, String user) throws Exception {
        client.connectServer("localhost", port, true);
        events.await(ChatClient.State.SERVER_CONNECTED);
        client.login(user, "test123!");
        events.await(ChatClient.State.CONNECTED);
    }

    private ChatServer server(TlsTestIdentity identity, int unauth, int perAccount, int authSeconds, int registrations) throws IOException {
        ChatServer server = new ChatServer("127.0.0.1", 0, 12, Duration.ofSeconds(5));
        server.publicAccess(new PublicAccess(identity.server, unauth, 40, 20, registrations, perAccount, 2, authSeconds));
        server.start();
        return server;
    }

    private static ChatClient client(Events listener, SSLContext context) {
        return new ChatClient(listener, Runnable::run, 2500, 4000, HeartbeatSettings.fromSystemProperties(), context);
    }

    private static final class Events implements ClientEventListener {
        private record Change(ChatClient.State state, String detail) { }
        final BlockingQueue<Change> states = new LinkedBlockingQueue<>();
        final BlockingQueue<Message> errors = new LinkedBlockingQueue<>(), messages = new LinkedBlockingQueue<>();
        final BlockingQueue<ChatRecord> history = new LinkedBlockingQueue<>();
        final BlockingQueue<String> deletions = new LinkedBlockingQueue<>();
        final BlockingQueue<Boolean> completed = new LinkedBlockingQueue<>();
        @Override public void onStateChanged(ChatClient.State state, String detail) { states.add(new Change(state, detail)); }
        @Override public void onError(Message message) { errors.add(message); }
        @Override public void onGroup(Message message) { messages.add(message); }
        @Override public void onPrivate(Message message) { messages.add(message); }
        @Override public void onHistoryRecord(ChatRecord record) { history.add(record); }
        @Override public void onHistoryDeleted(String scope, String id) { deletions.add(id); }
        @Override public void onHistoryComplete() { completed.add(true); }
        String await(ChatClient.State expected) throws InterruptedException {
            Change event;
            do {
                event = states.poll(5, TimeUnit.SECONDS);
                assertNotNull(event, "Missing state " + expected);
            } while (event.state() != expected);
            return event.detail();
        }
    }
}
