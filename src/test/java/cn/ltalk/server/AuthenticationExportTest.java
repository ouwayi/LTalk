package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.client.*;
import cn.ltalk.support.Peer;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AuthenticationExportTest {
    @TempDir Path directory;
    @Test void accountsValidatePersistSaltAndRandomNames() throws Exception {
        AccountStore store = new AccountStore(directory);
        assertEquals("alice", store.register("alice", "昵称", "abc!12", false));
        assertEquals("USER_EXISTS", assertThrows(ProtocolException.class, () -> store.register("alice", "昵称", "abc!12", false)).code());
        assertEquals("INVALID_USER_ID", assertThrows(ProtocolException.class, () -> store.register("../bad", "昵称", "abc!12", false)).code());
        assertEquals("INVALID_PASSWORD", assertThrows(ProtocolException.class, () -> store.register("b", "昵称", "12345", false)).code());
        assertEquals("MISSING_FIELD", assertThrows(ProtocolException.class, () -> store.register("b", null, "123456", false)).code());
        assertEquals("MISSING_FIELD", assertThrows(ProtocolException.class, () -> store.register(null, "昵称", "123456", false)).code());
        String generated = store.register(null, "随机", "abc!12", true);
        assertTrue(generated.matches("[A-Za-z0-9_]{1,20}"));
        assertNotEquals(generated, store.register(null, "随机", "abc!12", true));
        String data = Files.readString(directory.resolve(AccountStore.fileKey("alice") + ".json"));
        assertFalse(data.contains("abc!12")); assertTrue(data.contains("salt"));
        store.register("Alice", "另一个账号", "other!12", false);
        AccountStore loaded = new AccountStore(directory);
        assertEquals("Alice", loaded.authenticate("Alice", "other!12"));
        assertEquals("alice", loaded.authenticate("alice", "abc!12"));
        for (String id : List.of("alice", "unknown"))
            assertEquals("LOGIN_FAILED", assertThrows(ProtocolException.class, () -> loaded.authenticate(id, "wrong!12")).code());
    }
    @Test void concurrentRegistrationHasOneWinnerAndStorageFailureIsObservable() throws Exception {
        AccountStore store = new AccountStore(directory.resolve("accounts"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int n = 0; n < 2; n++) results.add(pool.submit(() -> {
                try { return store.register("race", "昵称", "123456", false); }
                catch (ProtocolException ex) { return ex.code(); }
            }));
            assertEquals(Set.of("race", "USER_EXISTS"), Set.of(results.get(0).get(), results.get(1).get()));
        } finally { pool.shutdownNow(); }
        Files.createDirectory(directory.resolve("accounts").resolve(AccountStore.fileKey("blocked") + ".json"));
        assertThrows(IOException.class, () -> store.register("blocked", "昵称", "123456", false));
    }
    @Test void wireRegistrationAuthenticationAndAccessChecks() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            try (Peer peer = new Peer(server.port())) {
                peer.send(Message.of(MessageType.EXPORT_REQUEST).requestId("e").build()); peer.error("NOT_AUTHENTICATED");
                peer.send(Message.of(MessageType.REGISTER).requestId("r").nickname("你好").password("pass!12").usernameMode("RANDOM").build());
                String id = peer.type(MessageType.REGISTER_OK).userId();
                peer.send(Message.of(MessageType.LOGIN).requestId("l").userId(id).password("pass!12").build());
                assertEquals(id, peer.type(MessageType.LOGIN_OK).userId());
                peer.send(Message.of(MessageType.EXPORT_REQUEST).requestId("e2").userId("victim").build()); peer.error("FORBIDDEN");
                peer.send(Message.of(MessageType.EXPORT_REQUEST).requestId("e3").cursor("forged").build()); peer.error("FORBIDDEN");
                peer.send(Message.of(MessageType.EXPORT_REQUEST).requestId("empty").build()); peer.type(MessageType.EXPORT_END);
                peer.send(Message.of(MessageType.LOGOUT).build()); while (peer.read() != null) { }
            }
            try (Peer invalid = new Peer(server.port())) {
                invalid.send(Message.of(MessageType.LOGIN).requestId("bad").userId("unknown").password("wrong!12").build());
                invalid.error("LOGIN_FAILED"); assertNull(invalid.read());
            }
        }
    }
    @Test void exportJournalsArePrivateDurableOrderedAndBoundedPages() throws Exception {
        Path data = directory.resolve("server");
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(data); server.start();
            try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob"); Peer c = Peer.login(server.port(), "carol")) {
                a.group("公共\n🙂,\"<html>");
                for (Peer p : List.of(a,b,c)) p.type(MessageType.GROUP_MESSAGE);
                a.privateTo("bob", "secret"); a.type(MessageType.PRIVATE_MESSAGE); b.type(MessageType.PRIVATE_MESSAGE);
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("cursor-owner").build());
                String owned = a.type(MessageType.EXPORT_PAGE).cursor();
                c.send(Message.of(MessageType.EXPORT_REQUEST).requestId("stolen").cursor(owned).build()); c.error("FORBIDDEN");
                List<ChatRecord> alice = records(a), carol = records(c);
                assertEquals(2, alice.size()); assertEquals(1, carol.size());
                assertEquals("secret", alice.get(1).content()); assertEquals("bob", alice.get(1).recipient());
                assertNotEquals(alice.get(0).messageId(), alice.get(1).messageId());
                assertTrue(alice.get(0).sentAt().compareTo(alice.get(1).sentAt()) <= 0);
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("reuse").cursor("old").build()); a.error("FORBIDDEN");
            }
        }
        try (ChatServer restarted = new ChatServer("127.0.0.1", 0)) {
            restarted.dataDirectory(data); restarted.start();
            try (Peer a = Peer.login(restarted.port(), "alice")) { assertEquals(2, records(a).size()); }
        }
    }
    @Test void clientStreamsJsonAndCsvAndReportsFileFailure() throws Exception {
        BlockingQueue<Path> completed = new LinkedBlockingQueue<>();
        BlockingQueue<Message> errors = new LinkedBlockingQueue<>();
        CountDownLatch connected = new CountDownLatch(1);
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); server.accounts().register("alice", "昵称", "pass!12", false);
            try (ChatClient client = new ChatClient(new ClientEventListener() {
                public void onStateChanged(ChatClient.State state, String detail) { if (state == ChatClient.State.CONNECTED) connected.countDown(); }
                public void onExported(Path path) { completed.add(path); }
                public void onError(Message error) { errors.add(error); }
            }, Runnable::run)) {
                client.connect("127.0.0.1", server.port(), "alice", "pass!12"); assertTrue(connected.await(4, TimeUnit.SECONDS));
                client.exportHistory(directory, "json"); Path empty = completed.poll(4, TimeUnit.SECONDS);
                assertNotNull(empty); assertEquals("[]", Files.readString(empty));
                try (Peer bob = Peer.login(server.port(), "bob")) {
                    bob.group("特殊,\"\n🙂"); bob.type(MessageType.GROUP_MESSAGE);
                    client.exportHistory(directory, "json"); Path json = completed.poll(4, TimeUnit.SECONDS); assertNotNull(json);
                    assertEquals("特殊,\"\n🙂", JsonParser.parseString(Files.readString(json)).getAsJsonArray().get(0).getAsJsonObject().get("content").getAsString());
                    client.exportHistory(directory, "csv"); Path csv = completed.poll(4, TimeUnit.SECONDS); assertNotNull(csv);
                    assertTrue(Files.readString(csv).contains("\"特殊,\"\"\n🙂\""));
                    assertTrue(csv.getFileName().toString().startsWith("alice_"));
                    client.exportHistory(directory.resolve("missing"), "json");
                    assertEquals("EXPORT_FAILED", errors.poll(4, TimeUnit.SECONDS).code());
                    assertEquals(ChatClient.State.CONNECTED, client.state());
                }
            }
        }
    }
    private List<ChatRecord> records(Peer peer) throws IOException {
        return records(peer, null);
    }
    private List<ChatRecord> records(Peer peer, String scope) throws IOException {
        List<ChatRecord> result = new ArrayList<>(); String cursor = null;
        do {
            peer.send(Message.of(MessageType.EXPORT_REQUEST).requestId("export").cursor(cursor).conversationId(scope).build());
            Message m = peer.await(r -> "export".equals(r.requestId()));
            if (m.type() == MessageType.EXPORT_END) return result;
            assertEquals(MessageType.EXPORT_PAGE, m.type()); result.add(m.record()); cursor = m.cursor();
        } while (result.size() < 1000);
        throw new AssertionError("Unbounded export");
    }
    @Test void conversationExportsFilterBothDirectionsAndBindScopeToCursor() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob"); Peer c = Peer.login(server.port(), "carol")) {
                a.group("public"); for (Peer p : List.of(a, b, c)) p.type(MessageType.GROUP_MESSAGE);
                a.privateTo("bob", "outbound"); a.type(MessageType.PRIVATE_MESSAGE); b.type(MessageType.PRIVATE_MESSAGE);
                b.privateTo("alice", "inbound"); a.type(MessageType.PRIVATE_MESSAGE); b.type(MessageType.PRIVATE_MESSAGE);
                a.privateTo("carol", "other"); a.type(MessageType.PRIVATE_MESSAGE); c.type(MessageType.PRIVATE_MESSAGE);
                String scope = ConversationScope.privateChat("alice", "bob");
                assertEquals(List.of("public"), records(a, ConversationScope.PUBLIC).stream().map(ChatRecord::content).toList());
                assertEquals(List.of("outbound", "inbound"), records(a, scope).stream().map(ChatRecord::content).toList());
                assertTrue(records(a, ConversationScope.privateChat("alice", "nobody")).isEmpty());
                c.send(Message.of(MessageType.EXPORT_REQUEST).requestId("forbidden").conversationId(scope).build()); c.error("FORBIDDEN");
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("start").conversationId(scope).build());
                String cursor = a.type(MessageType.EXPORT_PAGE).cursor();
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("changed").cursor(cursor).conversationId(ConversationScope.PUBLIC).build()); a.error("FORBIDDEN");
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("continue").cursor(cursor).conversationId(scope).build());
                assertEquals("inbound", a.type(MessageType.EXPORT_PAGE).record().content());
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("bad").conversationId("PRIVATE:bob:alice").build()); a.error("BAD_MESSAGE");
            }
        }
    }
    @Test void clientConversationExportWritesOnlySelectedRecords() throws Exception {
        BlockingQueue<Path> completed = new LinkedBlockingQueue<>(); CountDownLatch ready = new CountDownLatch(1);
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); server.accounts().register("alice", "昵称", "pass!12", false);
            try (ChatClient client = new ChatClient(new ClientEventListener() {
                public void onStateChanged(ChatClient.State s, String detail) { if (s == ChatClient.State.CONNECTED) ready.countDown(); }
                public void onExported(Path path) { completed.add(path); }
            }, Runnable::run); Peer bob = Peer.login(server.port(), "bob")) {
                client.connect("127.0.0.1", server.port(), "alice", "pass!12"); assertTrue(ready.await(4, TimeUnit.SECONDS));
                client.sendGroup("group only"); bob.type(MessageType.GROUP_MESSAGE);
                client.sendPrivate("bob", "private only"); bob.type(MessageType.PRIVATE_MESSAGE);
                for (String format : List.of("json", "csv")) {
                    client.exportHistory(directory, format, ConversationScope.PUBLIC);
                    Path path = completed.poll(4, TimeUnit.SECONDS); assertNotNull(path);
                    assertTrue(Files.readString(path).contains("group only")); assertFalse(Files.readString(path).contains("private only"));
                }
                client.exportHistory(directory, "json", ConversationScope.privateChat("alice", "bob"));
                Path path = completed.poll(4, TimeUnit.SECONDS); assertNotNull(path);
                var records = JsonParser.parseString(Files.readString(path)).getAsJsonArray(); assertEquals(1, records.size());
                assertEquals("private only", records.get(0).getAsJsonObject().get("content").getAsString());
            }
        }
    }
    @Test void exportSnapshotAndLargeFilesAreIncrementalAndAbortRemovesPartialFile() throws Exception {
        ChatArchive archive = new ChatArchive(directory.resolve("history"));
        try (ChatArchive.Cursor empty = archive.open("Alice")) { assertNull(empty.next()); }
        Message request = Message.of(MessageType.GROUP_SEND).content("\"\n中文🙂").build();
        for (int i = 0; i < 400; i++) archive.record(MessageType.GROUP_MESSAGE, "alice", request, List.of("alice"));
        try (ChatArchive.Cursor cursor = archive.open("alice")) {
            archive.record(MessageType.GROUP_MESSAGE, "alice", request, List.of("alice"));
            int count = 0;
            while (cursor.next() != null) count++;
            assertEquals(400, count, "Snapshot must exclude subsequent appends");
            try (ChatArchive.Cursor other = archive.open("Alice")) { assertNull(other.next()); }
        }
        ChatRecord record = new ChatRecord("id", "GROUP:PUBLIC", "alice", null, "\"\n中文🙂", "GROUP_MESSAGE", "2026-10-29T00:00:00Z");
        try (HistoryExport export = new HistoryExport(directory, "alice", "json")) {
            for (int i = 0; i < 10_000; i++) export.append(record);
            Path file = export.finish(); assertTrue(Files.size(file) > 1_000_000);
            try (var reader = new com.google.gson.stream.JsonReader(Files.newBufferedReader(file))) {
                reader.beginArray(); int count = 0;
                while (reader.hasNext()) { reader.skipValue(); count++; }
                reader.endArray(); assertEquals(10_000, count);
            }
        }
        try (HistoryExport aborted = new HistoryExport(directory, "alice", "csv")) { aborted.append(record); }
        try (var files = Files.list(directory)) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp"))); }
        try (HistoryExport empty = new HistoryExport(directory, "alice", "csv")) {
            assertEquals("messageId,conversationId,sender,recipient,content,messageType,sentAt\r\n", Files.readString(empty.finish()));
        }
    }
    @Test void corruptHistoryAndDataDirectoryConflictAreReported() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory); server.start();
            try (ChatServer conflict = new ChatServer("127.0.0.1", 0)) {
                conflict.dataDirectory(directory); assertThrows(IOException.class, conflict::start);
            }
            try (Peer a = Peer.login(server.port(), "alice")) {
                Files.writeString(directory.resolve("history").resolve(AccountStore.fileKey("alice") + ".jsonl"), "broken\n");
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("e").build()); a.error("EXPORT_FAILED");
                a.group("connection survives export failure"); a.type(MessageType.GROUP_MESSAGE);
            }
        }
    }
}
