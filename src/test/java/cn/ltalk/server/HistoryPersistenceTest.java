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

    @Test void replyReferencesSurviveRestartAndMustPointToAVisibleMessageInTheSameConversation() throws Exception {
        String originalId, privateId, replyId;
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory);
            server.start();
            try (Peer alice = Peer.login(server.port(), "alice"); Peer bob = Peer.login(server.port(), "bob")) {
                SocialFixtures.permitPrivate(server, "alice", "bob");
                bob.group("公开的原消息");
                originalId = bob.type(MessageType.GROUP_MESSAGE).messageId();
                alice.type(MessageType.GROUP_MESSAGE);
                bob.privateTo("alice", "仅双方可见的正文");
                privateId = bob.type(MessageType.PRIVATE_MESSAGE).messageId();
                alice.type(MessageType.PRIVATE_MESSAGE);
                alice.send(Message.of(MessageType.GROUP_SEND).requestId("wrong-scope").content("公开回复").replyTo(privateId).build());
                alice.error("NOT_FOUND");
                alice.send(Message.of(MessageType.GROUP_SEND).requestId("malformed").content("坏编号").replyTo("invalid").build());
                alice.error("BAD_MESSAGE");
                alice.send(Message.of(MessageType.PRIVATE_SEND).requestId("private-reply").to("bob")
                        .content("私聊回复").replyTo(privateId).build());
                assertEquals(privateId, alice.type(MessageType.PRIVATE_MESSAGE).replyTo());
                assertEquals(privateId, bob.type(MessageType.PRIVATE_MESSAGE).replyTo());
                alice.send(Message.of(MessageType.GROUP_SEND).requestId("public-reply").content("公开回复").replyTo(originalId).build());
                replyId = alice.type(MessageType.GROUP_MESSAGE).messageId();
                Message event = bob.type(MessageType.GROUP_MESSAGE);
                assertEquals(originalId, event.replyTo());
                assertFalse(MessageCodec.encode(event).contains("公开的原消息"));
                bob.send(Message.of(MessageType.HISTORY_DELETE).requestId("hide").conversationId(ConversationScope.PUBLIC)
                        .messageId(originalId).build());
                bob.type(MessageType.HISTORY_DELETED);
                bob.send(Message.of(MessageType.GROUP_SEND).requestId("hidden").content("回复已删除记录").replyTo(originalId).build());
                bob.error("NOT_FOUND");
            }
        }
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory);
            server.start();
            try (Peer bob = Peer.login(server.port(), "bob")) {
                List<ChatRecord> history = records(bob);
                assertEquals(3, history.size());
                assertTrue(history.stream().noneMatch(row -> row.messageId().equals(originalId)));
                assertEquals(originalId, history.stream().filter(row -> row.messageId().equals(replyId)).findFirst().orElseThrow().replyTo());
                assertEquals(privateId, history.stream().filter(row -> row.content().equals("私聊回复")).findFirst().orElseThrow().replyTo());
            }
        }
    }

    private record View(List<ChatRecord> rows, String next) { }

    private static View view(Peer peer, HistoryQuery mode, String scope, String cursor, String query, String anchor) throws Exception {
        peer.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("view").historyMode(mode.name())
                .conversationId(scope).cursor(cursor).content(query).messageId(anchor).build());
        List<ChatRecord> rows = new ArrayList<>();
        while (true) {
            Message response = peer.await(m -> "view".equals(m.requestId()));
            if (response.type() == MessageType.HISTORY_VIEW_END) return new View(rows, response.cursor());
            assertEquals(MessageType.HISTORY_VIEW_ROW, response.type(), () -> "Unexpected view response: " + response);
            assertTrue(rows.size() < HistoryQuery.PAGE_SIZE);
            rows.add(response.record());
        }
    }

    @Test void boundedViewsPageAndLocateOnlyVisibleMessagesInTheSelectedScope() throws Exception {
        String group = ConversationScope.PUBLIC;
        String privateScope = ConversationScope.privateChat("alice", "bob");
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory);
            server.start();
            try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob");
                 Peer c = Peer.login(server.port(), "carol")) {
                a.send(Message.of(MessageType.PROFILE_UPDATE).requestId("name").nickname("Project Alice").avatar("").build());
                assertEquals(MessageType.PROFILE_OK, a.await(m -> "name".equals(m.requestId())).type());
                List<String> ids = new ArrayList<>();
                String unicode = "汉".repeat(700) + "\n😀";
                for (int i = 1; i <= 28; i++) {
                    a.group(i + " " + unicode);
                    ids.add(a.type(MessageType.GROUP_MESSAGE).messageId());
                    b.type(MessageType.GROUP_MESSAGE);
                    c.type(MessageType.GROUP_MESSAGE);
                }
                SocialFixtures.permitPrivate(server, "alice", "bob");
                a.privateTo("bob", "private-only-keyword");
                Message secret = a.type(MessageType.PRIVATE_MESSAGE);
                b.type(MessageType.PRIVATE_MESSAGE);
                View summaries = view(a, HistoryQuery.SUMMARIES, null, null, null, null);
                assertEquals(List.of(ids.get(27), secret.messageId()), summaries.rows.stream().map(ChatRecord::messageId).toList());
                assertNull(summaries.next);
                assertEquals(1, view(c, HistoryQuery.SUMMARIES, null, null, null, null).rows.size());

                View recent = view(a, HistoryQuery.MESSAGES, group, null, null, null);
                assertEquals(20, recent.rows.size());
                assertEquals("28 " + unicode, recent.rows.get(0).content());
                assertEquals(ids.get(8), recent.next);
                b.group("a new live message between pages");
                b.type(MessageType.GROUP_MESSAGE);
                View older = view(a, HistoryQuery.MESSAGES, group, recent.next, null, null);
                assertEquals(ids.get(7), older.rows.get(0).messageId());
                assertEquals(ids.get(0), older.rows.get(7).messageId());
                assertNull(older.next);

                View search = view(a, HistoryQuery.SEARCH, group, null, "PROJECT ALICE", null);
                assertEquals(recent.next, search.next);
                assertEquals(8, view(a, HistoryQuery.SEARCH, group, search.next, "PROJECT ALICE", null).rows.size());
                assertTrue(view(a, HistoryQuery.SEARCH, group, null, "private-only", null).rows.isEmpty());
                assertEquals(List.of(secret.messageId()), view(a, HistoryQuery.SEARCH, privateScope, null, "private-only", null)
                        .rows.stream().map(ChatRecord::messageId).toList());
                String target = ids.get(5);
                View context = view(a, HistoryQuery.CONTEXT, group, null, null, target);
                assertEquals(ids.get(14), context.rows.get(0).messageId());
                assertEquals(target, context.rows.get(9).messageId());
                assertEquals(ids.get(0), context.rows.get(context.rows.size() - 1).messageId());
                c.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("foreign").historyMode("CONTEXT")
                        .conversationId(privateScope).messageId(secret.messageId()).build());
                c.error("FORBIDDEN");
                a.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("mismatch").historyMode("CONTEXT")
                        .conversationId(group).messageId(secret.messageId()).build());
                a.error("NOT_FOUND");
                a.send(Message.of(MessageType.HISTORY_DELETE).requestId("hide").conversationId(group).messageId(target).build());
                a.type(MessageType.HISTORY_DELETED);
                assertTrue(view(a, HistoryQuery.SEARCH, group, null, "6 " + unicode.substring(0, 10), null)
                        .rows.stream().noneMatch(row -> row.messageId().equals(target)));
                a.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("deleted").historyMode("CONTEXT")
                        .conversationId(group).messageId(target).build());
                a.error("NOT_FOUND");
                assertTrue(view(b, HistoryQuery.CONTEXT, group, null, null, target).rows.stream().anyMatch(row -> row.messageId().equals(target)));
            }
        }
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
                    SocialFixtures.permitPrivate(server, "alice", "bob");
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

    @Test void sharedPublicDeletionAndClearStayPersonalAcrossRestart() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory); server.start();
            try (Peer alice = Peer.login(server.port(), "alice"); Peer bob = Peer.login(server.port(), "bob")) {
                alice.group("共享公共消息"); String shared = alice.type(MessageType.GROUP_MESSAGE).messageId();
                bob.type(MessageType.GROUP_MESSAGE);
                assertEquals(List.of(shared), records(alice).stream().map(ChatRecord::messageId).toList());
                assertEquals(List.of(shared), records(bob).stream().map(ChatRecord::messageId).toList());
                assertEquals(List.of(shared), view(alice, HistoryQuery.MESSAGES, ConversationScope.PUBLIC, null, null, null)
                        .rows.stream().map(ChatRecord::messageId).toList());
                alice.send(Message.of(MessageType.HISTORY_DELETE).requestId("one").conversationId(ConversationScope.PUBLIC)
                        .messageId(shared).build()); alice.type(MessageType.HISTORY_DELETED);
                assertTrue(records(alice).isEmpty());
                assertTrue(view(alice, HistoryQuery.SEARCH, ConversationScope.PUBLIC, null, "共享", null).rows.isEmpty());
                assertEquals(List.of(shared), records(bob).stream().map(ChatRecord::messageId).toList());
                bob.group("清空前的新消息"); alice.type(MessageType.GROUP_MESSAGE); bob.type(MessageType.GROUP_MESSAGE);
                assertEquals(List.of("清空前的新消息"), records(alice).stream().map(ChatRecord::content).toList());
                alice.send(Message.of(MessageType.HISTORY_DELETE).requestId("clear").conversationId(ConversationScope.PUBLIC).build());
                alice.type(MessageType.HISTORY_DELETED);
                assertTrue(records(alice).isEmpty());
                assertTrue(view(alice, HistoryQuery.MESSAGES, ConversationScope.PUBLIC, null, null, null).rows.isEmpty());
            }
        }
        try (ChatServer restarted = new ChatServer("127.0.0.1", 0)) {
            restarted.dataDirectory(directory); restarted.start();
            try (Peer alice = Peer.login(restarted.port(), "alice"); Peer bob = Peer.login(restarted.port(), "bob")) {
                bob.group("清空后的新消息"); alice.type(MessageType.GROUP_MESSAGE); bob.type(MessageType.GROUP_MESSAGE);
                assertEquals(List.of("清空后的新消息"), records(alice).stream().map(ChatRecord::content).toList());
                assertEquals(List.of("共享公共消息", "清空前的新消息", "清空后的新消息"), records(bob).stream().map(ChatRecord::content).toList());
            }
        }
    }
    @Test void deleteOneAndClearConversationAffectOnlyOwnerAndRemainDeletedAfterRestart() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory); server.start();
            try (Peer a = Peer.login(server.port(), "alice"); Peer b = Peer.login(server.port(), "bob"); Peer c = Peer.login(server.port(), "carol")) {
                a.group("public"); for (Peer p : List.of(a,b,c)) p.type(MessageType.GROUP_MESSAGE);
                SocialFixtures.permitPrivate(server, "alice", "bob");
                SocialFixtures.permitPrivate(server, "alice", "carol");
                a.privateTo("bob", "secret"); Message secret = a.type(MessageType.PRIVATE_MESSAGE); b.type(MessageType.PRIVATE_MESSAGE);
                c.privateTo("alice", "keep"); a.type(MessageType.PRIVATE_MESSAGE); c.type(MessageType.PRIVATE_MESSAGE);
                List<ChatRecord> other = records(b);
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
                assertEquals(other, records(b));
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
    @Test void failedClearRollsBackBothWatermarkAndPersonalDeletion() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory); server.start();
            try (Peer a = Peer.login(server.port(), "alice")) {
                a.group("hidden"); Message hidden = a.type(MessageType.GROUP_MESSAGE);
                a.group("visible"); a.type(MessageType.GROUP_MESSAGE);
                a.send(Message.of(MessageType.HISTORY_DELETE).requestId("hide").conversationId(ConversationScope.PUBLIC)
                        .messageId(hidden.messageId()).build());
                a.type(MessageType.HISTORY_DELETED);
                SqliteFixtures.edit(directory, """
                        CREATE TRIGGER fail_clear BEFORE DELETE ON hidden_messages
                        BEGIN SELECT RAISE(ABORT, 'test write failure'); END
                        """);
                a.send(Message.of(MessageType.HISTORY_DELETE).requestId("d").conversationId(ConversationScope.PUBLIC).build()); a.error("STORAGE_ERROR");
                assertEquals(List.of("visible"), records(a).stream().map(ChatRecord::content).toList());
                assertEquals(0, SqliteFixtures.count(directory, "SELECT count(*) FROM history_clears"));
                assertEquals(1, SqliteFixtures.count(directory, "SELECT count(*) FROM hidden_messages"));
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
