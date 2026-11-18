package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class MessageRecallTest {
    @TempDir Path directory;

    @Test void recallPersistsAndUpdatesPrivateParticipantsWithoutLeakingToOthers() throws Exception {
        String scope = ConversationScope.privateChat("alice", "bob");
        String id;
        try (ChatServer server = start(); Peer alice = Peer.login(server.port(), "alice");
             Peer bob = Peer.login(server.port(), "bob"); Peer carol = Peer.login(server.port(), "carol")) {
            SocialFixtures.permitPrivate(server, "alice", "bob");
            alice.privateTo("bob", "one");
            bob.type(MessageType.PRIVATE_MESSAGE);
            alice.type(MessageType.PRIVATE_MESSAGE);
            alice.privateTo("bob", "content that must disappear");
            id = alice.type(MessageType.PRIVATE_MESSAGE).messageId();
            assertEquals(id, bob.type(MessageType.PRIVATE_MESSAGE).messageId());

            Message recall = request(scope, id);
            bob.send(recall);
            bob.error("NOT_FOUND");
            carol.send(recall);
            carol.error("FORBIDDEN");
            alice.send(request(ConversationScope.PUBLIC, id));
            alice.error("NOT_FOUND");

            bob.send(Message.of(MessageType.EXPORT_REQUEST).requestId("export").conversationId(scope).build());
            Message first = bob.type(MessageType.EXPORT_PAGE);
            assertEquals("one", first.record().content());
            alice.send(recall);
            assertEquals(recall.requestId(), alice.type(MessageType.MESSAGE_RECALLED).requestId());
            Message notice = bob.type(MessageType.MESSAGE_RECALLED);
            assertEquals(id, notice.messageId());
            assertEquals(scope, notice.conversationId());
            assertNull(notice.requestId());
            carol.assertNo(frame -> frame.type() == MessageType.MESSAGE_RECALLED, 100);

            bob.send(Message.of(MessageType.EXPORT_REQUEST).requestId("export").conversationId(scope).cursor(first.cursor()).build());
            ChatRecord exported = bob.type(MessageType.EXPORT_PAGE).record();
            assertTrue(exported.isRecalled());
            assertEquals(ChatRecord.RECALLED_CONTENT, exported.content());
            assertEquals(id, exported.messageId());
            bob.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("search").historyMode("SEARCH")
                    .conversationId(scope).content("content that must disappear").build());
            assertEquals(MessageType.HISTORY_VIEW_END, bob.await(frame -> "search".equals(frame.requestId())).type());
            bob.send(Message.of(MessageType.PRIVATE_SEND).requestId("reply").to("alice").content("reply").replyTo(id).build());
            bob.error("NOT_FOUND");
            alice.send(recall);
            assertEquals(id, alice.type(MessageType.MESSAGE_RECALLED).messageId());
            bob.assertNo(frame -> frame.type() == MessageType.MESSAGE_RECALLED, 100);
        }
        try (ChatServer server = start(); Peer bob = Peer.login(server.port(), "bob")) {
            bob.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("context").historyMode("CONTEXT").conversationId(scope)
                    .messageId(id).build());
            Message restored = bob.type(MessageType.HISTORY_VIEW_ROW);
            assertEquals(id, restored.record().messageId());
            assertTrue(restored.record().isRecalled());
            assertEquals(ChatRecord.RECALLED_CONTENT, restored.record().content());
            assertNull(restored.record().attachment());
        }
    }

    @Test void allowsExactlySixtySecondsAndRejectsTheFirstInstantAfter() throws Exception {
        try (ChatServer server = start(); Peer alice = Peer.login(server.port(), "alice")) {
            alice.group("public message");
            Message sent = alice.type(MessageType.GROUP_MESSAGE);
            Instant limit = Instant.parse(sent.serverTime()).plusSeconds(60);
            var archive = server.archive();
            var expired = assertThrows(ProtocolException.class,
                    () -> archive.recall("alice", ConversationScope.PUBLIC, sent.messageId(), limit.plusNanos(1)));
            assertEquals("RECALL_EXPIRED", expired.code());
            assertEquals("public message", archive.visibleRecord("alice", ConversationScope.PUBLIC, sent.messageId()).content());
            assertTrue(archive.recall("alice", ConversationScope.PUBLIC, sent.messageId(), limit).changed());
            assertFalse(archive.recall("alice", ConversationScope.PUBLIC, sent.messageId(), limit.plusSeconds(1)).changed());
        }
    }

    private ChatServer start() throws Exception {
        ChatServer server = new ChatServer("127.0.0.1", 0);
        server.dataDirectory(directory);
        server.start();
        return server;
    }

    private static Message request(String scope, String id) {
        return Message.of(MessageType.MESSAGE_RECALL).requestId("recall").conversationId(scope).messageId(id).build();
    }
}
