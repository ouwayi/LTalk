package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.support.Peer;
import com.google.gson.Gson;
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

    @Test void lateRegistrationAndOfflineLoginCatchUpWithPublicHistoryOnly() throws Exception {
        List<String> originalIds = new ArrayList<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory); server.start();
            try (Peer alice = Peer.login(server.port(), "alice")) {
                for (int i = 1; i <= 4; i++) {
                    alice.group("公共消息 " + i);
                    originalIds.add(alice.type(MessageType.GROUP_MESSAGE).messageId());
                }
                try (Peer bob = Peer.login(server.port(), "bob")) {
                    assertEquals(originalIds, records(bob).stream().map(ChatRecord::messageId).toList());
                    alice.privateTo("bob", "仅双方");
                    alice.type(MessageType.PRIVATE_MESSAGE); bob.type(MessageType.PRIVATE_MESSAGE);
                    try (Peer carol = Peer.login(server.port(), "carol")) {
                        String cursor = null;
                        for (String id : originalIds) {
                            carol.send(Message.of(MessageType.EXPORT_REQUEST).requestId("public-export")
                                    .conversationId(ConversationScope.PUBLIC).cursor(cursor).build());
                            Message page = carol.type(MessageType.EXPORT_PAGE);
                            assertEquals(id, page.record().messageId());
                            cursor = page.cursor();
                        }
                        carol.send(Message.of(MessageType.EXPORT_REQUEST).requestId("public-export")
                                .conversationId(ConversationScope.PUBLIC).cursor(cursor).build());
                        carol.type(MessageType.EXPORT_END);
                        assertEquals(4, records(carol).size());
                    }
                    alice.close();
                    bob.await(m -> m.type() == MessageType.USER_LIST && !m.users().contains("alice"));
                    bob.privateTo("alice", "不得创建离线私聊"); bob.error("USER_OFFLINE");
                    bob.group("离线期间的公共消息");
                    originalIds.add(bob.type(MessageType.GROUP_MESSAGE).messageId());
                }
            }
        }
        try (ChatServer restarted = new ChatServer("127.0.0.1", 0)) {
            restarted.dataDirectory(directory); restarted.start();
            try (Peer alice = Peer.login(restarted.port(), "alice")) {
                List<ChatRecord> all = records(alice);
                assertEquals(List.of("公共消息 1", "公共消息 2", "公共消息 3", "公共消息 4", "仅双方", "离线期间的公共消息"),
                        all.stream().map(ChatRecord::content).toList());
                assertEquals(originalIds, all.stream().filter(record -> ConversationScope.PUBLIC.equals(record.conversationId()))
                        .map(ChatRecord::messageId).toList());
            }
        }
    }

    @Test void legacyPublicRecordsKeepTheirVisibilityAndPersonalDeletionSurvivesRestart() throws Exception {
        Path history = Files.createDirectory(directory.resolve("history"));
        String time = "2099-01-01T00:00:00Z";
        ChatRecord legacy = new ChatRecord(UUID.randomUUID().toString(), ConversationScope.PUBLIC, "alice", null,
                "旧版仅 Alice 的副本", "GROUP_MESSAGE", time);
        Files.writeString(history.resolve("last-time"), time);
        Files.writeString(history.resolve(AccountStore.fileKey("alice") + ".jsonl"), new Gson().toJson(legacy) + "\n");
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory); server.start();
            try (Peer alice = Peer.login(server.port(), "alice"); Peer bob = Peer.login(server.port(), "bob")) {
                alice.group("共享公共消息"); String shared = alice.type(MessageType.GROUP_MESSAGE).messageId();
                bob.type(MessageType.GROUP_MESSAGE);
                assertEquals(List.of(legacy.messageId(), shared), records(alice).stream().map(ChatRecord::messageId).toList());
                assertEquals(List.of(shared), records(bob).stream().map(ChatRecord::messageId).toList());
                alice.send(Message.of(MessageType.HISTORY_DELETE).requestId("one").conversationId(ConversationScope.PUBLIC)
                        .messageId(shared).build()); alice.type(MessageType.HISTORY_DELETED);
                assertEquals(List.of(legacy), records(alice));
                assertEquals(List.of(shared), records(bob).stream().map(ChatRecord::messageId).toList());
                alice.send(Message.of(MessageType.HISTORY_DELETE).requestId("clear").conversationId(ConversationScope.PUBLIC).build());
                alice.type(MessageType.HISTORY_DELETED);
                assertTrue(records(alice).isEmpty());
            }
        }
        try (ChatServer restarted = new ChatServer("127.0.0.1", 0)) {
            restarted.dataDirectory(directory); restarted.start();
            try (Peer alice = Peer.login(restarted.port(), "alice"); Peer bob = Peer.login(restarted.port(), "bob")) {
                bob.group("清空后的新消息"); alice.type(MessageType.GROUP_MESSAGE); bob.type(MessageType.GROUP_MESSAGE);
                assertEquals(List.of("清空后的新消息"), records(alice).stream().map(ChatRecord::content).toList());
                assertEquals(List.of("共享公共消息", "清空后的新消息"), records(bob).stream().map(ChatRecord::content).toList());
            }
        }
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
