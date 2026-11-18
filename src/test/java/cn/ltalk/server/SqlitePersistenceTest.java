package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SqlitePersistenceTest {
    @TempDir Path directory;

    @Test void refusesLegacyDirectoryAndCanStartFromAnIndependentEmptyDirectory() throws Exception {
        Path legacy = Files.createDirectory(directory.resolve("legacy"));
        Path original = Files.writeString(legacy.resolve("social.json"), "{\"legacy\":true}");
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(legacy);
            assertTrue(assertThrows(IOException.class, server::start).getMessage().contains("新数据目录"));
        }
        assertEquals("{\"legacy\":true}", Files.readString(original));
        assertFalse(Files.exists(legacy.resolve(SqliteDatabase.FILE_NAME)));

        Path fresh = directory.resolve("new");
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(fresh);
            server.start();
            assertTrue(Files.isRegularFile(fresh.resolve(SqliteDatabase.FILE_NAME)));
            assertEquals(1, SqliteFixtures.count(fresh, "PRAGMA user_version"));
            try (Peer raw = new Peer(server.port())) {
                raw.send(Message.of(MessageType.LOGIN).requestId("empty").userId("alice").password("test123!").build());
                raw.error("LOGIN_FAILED");
            }
            try (Peer alice = Peer.login(server.port(), "alice")) {
                assertTrue(HistoryPersistenceTest.records(alice).isEmpty());
            }
        }
        SqliteFixtures.edit(fresh, "PRAGMA user_version = 2");
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(fresh);
            assertTrue(assertThrows(IOException.class, server::start).getMessage().contains("表结构版本"));
        }
        assertEquals(2, SqliteFixtures.count(fresh, "PRAGMA user_version"));
        assertEquals(1, SqliteFixtures.count(fresh, "SELECT count(*) FROM accounts"));
    }

    @Test void privateMessageAndBothVisibilityRowsCommitOrRollBackTogether() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory);
            server.start();
            try (Peer alice = Peer.login(server.port(), "alice"); Peer bob = Peer.login(server.port(), "bob")) {
                SocialFixtures.permitPrivate(server, "alice", "bob");
                SqliteFixtures.edit(directory, """
                        CREATE TRIGGER fail_second_recipient BEFORE INSERT ON private_visibility WHEN NEW.user_id = 'bob'
                        BEGIN SELECT RAISE(ABORT, 'test write failure'); END
                        """);
                alice.privateTo("bob", "rolled back");
                alice.error("STORAGE_ERROR");
                assertEquals(0, SqliteFixtures.count(directory, "SELECT count(*) FROM messages"));
                assertEquals(0, SqliteFixtures.count(directory, "SELECT count(*) FROM private_visibility"));
                assertTrue(HistoryPersistenceTest.records(alice).isEmpty());
                assertTrue(HistoryPersistenceTest.records(bob).isEmpty());

                SqliteFixtures.edit(directory, "DROP TRIGGER fail_second_recipient");
                alice.privateTo("bob", "committed");
                Message sent = alice.type(MessageType.PRIVATE_MESSAGE);
                assertEquals("committed", sent.content());
                assertEquals(sent.messageId(), bob.type(MessageType.PRIVATE_MESSAGE).messageId());
                assertEquals(1, SqliteFixtures.count(directory, "SELECT count(*) FROM messages"));
                assertEquals(2, SqliteFixtures.count(directory, "SELECT count(*) FROM private_visibility"));
            }
        }
    }

    @Test void acceptingFriendRequestCanBeRetriedAfterTransactionFailure() throws Exception {
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory);
            server.start();
            try (Peer alice = Peer.login(server.port(), "alice"); Peer bob = Peer.login(server.port(), "bob")) {
                alice.send(Message.of(MessageType.SOCIAL_REQUEST).requestId("friend").socialAction("FRIEND_REQUEST").to("bob").build());
                alice.await(m -> "friend".equals(m.requestId()) && m.type() == MessageType.SOCIAL_END);
                String id = server.social().items("bob", server.accounts()).stream()
                        .filter(item -> item.kind() == SocialItem.Kind.FRIEND_IN).findFirst().orElseThrow().id();
                SqliteFixtures.edit(directory, """
                        CREATE TRIGGER fail_request_removal BEFORE DELETE ON social_requests
                        BEGIN SELECT RAISE(ABORT, 'test write failure'); END
                        """);
                Message accept = Message.of(MessageType.SOCIAL_REQUEST).requestId("accept")
                        .socialAction("FRIEND_ACCEPT").messageId(id).build();
                bob.send(accept);
                bob.error("STORAGE_ERROR");
                assertFalse(server.social().friends("alice", "bob"));
                assertEquals(0, SqliteFixtures.count(directory, "SELECT count(*) FROM relationships"));
                assertEquals(1, SqliteFixtures.count(directory, "SELECT count(*) FROM social_requests"));

                SqliteFixtures.edit(directory, "DROP TRIGGER fail_request_removal");
                bob.send(accept);
                bob.await(m -> "accept".equals(m.requestId()) && m.type() == MessageType.SOCIAL_END);
                assertTrue(server.social().friends("alice", "bob"));
                assertEquals(1, SqliteFixtures.count(directory, "SELECT count(*) FROM relationships"));
                assertEquals(0, SqliteFixtures.count(directory, "SELECT count(*) FROM social_requests"));
            }
        }
    }
}
