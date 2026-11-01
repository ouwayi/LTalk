package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class HistoryPersistenceTest {
    @TempDir Path directory;
    static List<ChatRecord> records(Peer peer) throws Exception {
        List<ChatRecord> result = new ArrayList<>(); String cursor = null;
        do {
            peer.send(Message.of(MessageType.HISTORY_REQUEST).requestId("read").cursor(cursor).build());
            Message page = peer.await(m -> "read".equals(m.requestId()));
            if (page.type() == MessageType.HISTORY_END) return result;
            assertEquals(MessageType.HISTORY_PAGE, page.type()); result.add(page.record()); cursor = page.cursor();
        } while (result.size() < 1000);
        throw new AssertionError("unbounded history");
    }
    @Test void deleteOneAndClearConversationAffectOnlyOwnerAndRemainDeletedAfterRestart() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory); server.start();
            try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob"); Peer c = Peer.login(server.port(), "carol")) {
                a.group("public"); for (Peer p : List.of(a,b,c)) p.type(MessageType.GROUP_MESSAGE);
                a.privateTo("bob", "secret"); Message secret = a.type(MessageType.PRIVATE_MESSAGE); b.type(MessageType.PRIVATE_MESSAGE);
                c.privateTo("alice", "keep"); a.type(MessageType.PRIVATE_MESSAGE); c.type(MessageType.PRIVATE_MESSAGE);
                byte[] other = Files.readAllBytes(directory.resolve("history").resolve(AccountStore.fileKey("bob") + ".jsonl"));
                assertEquals(3, records(a).size());
                a.send(Message.of(MessageType.HISTORY_REQUEST).requestId("snapshot").build()); String historyCursor = a.type(MessageType.HISTORY_PAGE).cursor();
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("export").build()); String exportCursor = a.type(MessageType.EXPORT_PAGE).cursor();
                a.send(Message.of(MessageType.HISTORY_DELETE).requestId("delete").conversationId(secret.conversationId()).messageId(secret.messageId()).build()); a.type(MessageType.HISTORY_DELETED);
                a.send(Message.of(MessageType.HISTORY_REQUEST).requestId("old-h").cursor(historyCursor).build()); a.error("FORBIDDEN");
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("old-e").cursor(exportCursor).build()); a.error("FORBIDDEN");
                assertEquals(List.of("public", "keep"), records(a).stream().map(ChatRecord::content).toList());
                assertEquals(List.of("public", "secret"), records(b).stream().map(ChatRecord::content).toList());
                a.send(Message.of(MessageType.HISTORY_DELETE).requestId("clear").conversationId(ConversationScope.PUBLIC).build()); a.type(MessageType.HISTORY_DELETED);
                assertEquals(List.of("keep"), records(a).stream().map(ChatRecord::content).toList());
                assertArrayEquals(other, Files.readAllBytes(directory.resolve("history").resolve(AccountStore.fileKey("bob") + ".jsonl")));
                a.send(Message.of(MessageType.EXPORT_REQUEST).requestId("remaining").build()); assertEquals("keep", a.type(MessageType.EXPORT_PAGE).record().content());
            }
        }
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory); server.start();
            try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob")) {
                assertEquals(List.of("keep"), records(a).stream().map(ChatRecord::content).toList()); assertEquals(2, records(b).size());
            }
        }
    }
    @Test void unauthenticatedForgedAccountAndOtherPrivateConversationAreRejected() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            try (Peer raw = new Peer(server.port()); Peer a = Peer.login(server.port(), "alice")) {
                raw.send(Message.of(MessageType.HISTORY_REQUEST).requestId("r").build()); raw.error("NOT_AUTHENTICATED");
                raw.send(Message.of(MessageType.HISTORY_DELETE).requestId("d").conversationId(ConversationScope.PUBLIC).build()); raw.error("NOT_AUTHENTICATED");
                for (MessageType type : List.of(MessageType.HISTORY_REQUEST, MessageType.HISTORY_DELETE)) {
                    a.send(Message.of(type).requestId("other").userId("bob").conversationId(ConversationScope.PUBLIC).build()); a.error("FORBIDDEN");
                    a.send(Message.of(type).requestId("private").conversationId("PRIVATE:bob:carol").build()); a.error("FORBIDDEN");
                }
                a.send(Message.of(MessageType.HISTORY_DELETE).requestId("missing-scope").build()); a.error("BAD_MESSAGE");
                a.send(Message.of(MessageType.HISTORY_DELETE).requestId("missing-id").conversationId(ConversationScope.PUBLIC).messageId(UUID.randomUUID().toString()).build()); a.error("NOT_FOUND");
                assertTrue(records(a).isEmpty());
            }
        }
    }
    @Test void corruptFileDeletionFailureDoesNotPublishOrDamagePreviousFile() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory); server.start();
            try (Peer a = Peer.login(server.port(), "alice")) {
                Path file = directory.resolve("history").resolve(AccountStore.fileKey("alice") + ".jsonl"); Files.writeString(file, "broken\n");
                a.send(Message.of(MessageType.HISTORY_DELETE).requestId("d").conversationId(ConversationScope.PUBLIC).build()); a.error("STORAGE_ERROR");
                assertEquals("broken\n", Files.readString(file));
                try (var files = Files.list(file.getParent())) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
            }
        }
    }
    @Test void liveDeliveryCannotResurrectRecordsBeforeDeleteAcknowledgement() throws Exception {
        ExecutorService publisher = Executors.newSingleThreadExecutor();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob")) {
                b.group("before"); a.type(MessageType.GROUP_MESSAGE); b.type(MessageType.GROUP_MESSAGE);
                Future<?> sending = publisher.submit(() -> { try { for (int i=0;i<30;i++) { b.group("race-"+i); b.type(MessageType.GROUP_MESSAGE); } } catch(Exception ex) { throw new RuntimeException(ex); } });
                a.send(Message.of(MessageType.HISTORY_DELETE).requestId("clear").conversationId(ConversationScope.PUBLIC).build()); a.type(MessageType.HISTORY_DELETED); int split = a.received.size();
                sending.get(8, TimeUnit.SECONDS); List<ChatRecord> kept = records(a);
                Set<String> ids = new HashSet<>(kept.stream().map(ChatRecord::messageId).toList());
                assertTrue(a.received.subList(0,split).stream().filter(m -> m.type()==MessageType.GROUP_MESSAGE).noneMatch(m -> ids.contains(m.messageId())));
                assertTrue(a.received.subList(split,a.received.size()).stream().filter(m -> m.type()==MessageType.GROUP_MESSAGE).allMatch(m -> ids.contains(m.messageId())));
            }
        } finally { publisher.shutdownNow(); }
    }
}
