package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class SqlitePersistenceTest {
    @TempDir Path directory;

    @Test void cliUsesPreferredSqliteDirectoryAndRetainsEarlierDatabaseAsFallback() throws Exception {
        Path preferred = directory.resolve("data-sqlite");
        Path previous = Files.createDirectory(directory.resolve("data"));
        Path legacy = Files.createDirectory(previous.resolve("accounts"));
        assertEquals(preferred, ChatServer.cliDataDirectory(directory, null));
        Files.delete(legacy);
        try (SqliteDatabase ignored = new SqliteDatabase(previous)) {
            assertEquals(previous, ChatServer.cliDataDirectory(directory, null));
        }
        Files.createDirectory(preferred);
        try (SqliteDatabase ignored = new SqliteDatabase(preferred)) {
            assertEquals(preferred, ChatServer.cliDataDirectory(directory, null));
            assertEquals(previous, ChatServer.cliDataDirectory(directory, previous.toString()));
            assertEquals(directory.resolve("custom"), ChatServer.cliDataDirectory(directory, "custom"));
        }
    }

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
            assertEquals(2, SqliteFixtures.count(fresh, "PRAGMA user_version"));
            try (Peer raw = new Peer(server.port())) {
                raw.send(Message.of(MessageType.LOGIN).requestId("empty").userId("alice").password("test123!").build());
                raw.error("LOGIN_FAILED");
            }
            try (Peer alice = Peer.login(server.port(), "alice")) {
                assertTrue(HistoryPersistenceTest.records(alice).isEmpty());
            }
        }
        SqliteFixtures.edit(fresh, "PRAGMA user_version = 3");
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(fresh);
            assertTrue(assertThrows(IOException.class, server::start).getMessage().contains("表结构版本"));
        }
        assertEquals(3, SqliteFixtures.count(fresh, "PRAGMA user_version"));
        assertEquals(1, SqliteFixtures.count(fresh, "SELECT count(*) FROM accounts"));
    }

    @Test void upgradesUuidGroupsWithTheirMessagesPermissionsAndPendingRequests() throws Exception {
        String oldId = "d291b960-605c-4f55-ae9e-db5b3e063906";
        String first = "e291b960-605c-4f55-ae9e-db5b3e063906";
        String second = "f291b960-605c-4f55-ae9e-db5b3e063906";
        String date = Instant.now().toString();
        try (var schema = SqliteDatabase.class.getResourceAsStream("sqlite-schema-v1.sql");
             var connection = SqliteFixtures.connect(directory);
             var statement = connection.createStatement()) {
            for (String ddl : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";"))
                if (!ddl.isBlank()) statement.execute(ddl);
            statement.execute("PRAGMA application_id = 0x4c544c4b");
            statement.execute("PRAGMA user_version = 1");
            for (String user : List.of("alice", "bob", "carol")) {
                SqliteDatabase.execute(connection, "INSERT INTO accounts VALUES (?, ?, ?, ?, '', ?, 0)", user, user,
                        "AAAAAAAAAAAAAAAAAAAAAA==", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", date);
            }
            SqliteDatabase.execute(connection, "INSERT INTO chat_groups VALUES (?, '旧群', 'alice', 1, 0)", oldId);
            SqliteDatabase.execute(connection, "INSERT INTO group_memberships VALUES (?, 'alice', 0, 1, NULL), (?, 'bob', 0, 1, 2)", oldId, oldId);
            SqliteDatabase.execute(connection, "INSERT INTO social_requests VALUES (?, 'JOIN', 'carol', NULL, ?)",
                    "a291b960-605c-4f55-ae9e-db5b3e063906", oldId);
            SqliteDatabase.execute(connection, """
                    INSERT INTO messages (seq, message_id, conversation_id, group_id, sender, content, sent_at)
                    VALUES (1, ?, ?, ?, 'alice', '原消息', ?)
                    """, first, ConversationScope.group(oldId), oldId, date);
            SqliteDatabase.execute(connection, """
                    INSERT INTO messages (seq, message_id, conversation_id, group_id, sender, content, sent_at, reply_to,
                                          attachment_kind, attachment_name, attachment_size)
                    VALUES (2, ?, ?, ?, 'alice', '历史图片.jpg', ?, ?, 'IMAGE', '历史图片.jpg', 1)
                    """, second, ConversationScope.group(oldId), oldId, date, first);
            SqliteDatabase.execute(connection, "INSERT INTO history_clears VALUES ('bob', ?, 1)", ConversationScope.group(oldId));
            SqliteDatabase.execute(connection, "INSERT INTO hidden_messages VALUES ('bob', 2)");
        }
        String groupId;
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory);
            server.start();
            List<SocialItem> state = server.social().items("alice", server.accounts());
            groupId = state.stream().filter(row -> row.kind() == SocialItem.Kind.GROUP).findFirst().orElseThrow().group();
            assertTrue(groupId.matches("[0-9]{10}"));
            assertTrue(state.stream().anyMatch(row -> !row.id().equals(groupId) && groupId.equals(row.group())));
            String scope = ConversationScope.group(groupId);
            ChatRecord photo = server.archive().visibleRecord("alice", scope, second);
            assertEquals(first, photo.replyTo());
            assertEquals("[图片]", photo.content());
            assertEquals("图片.jpg", photo.attachment().name());
            assertNull(server.archive().visibleRecord("bob", scope, first));
            assertNull(server.archive().visibleRecord("bob", scope, second));
            assertEquals("FORBIDDEN", assertThrows(ProtocolException.class,
                    () -> server.archive().visibleRecord("carol", scope, first)).code());
        }
        assertEquals(2, SqliteFixtures.count(directory, "PRAGMA user_version"));
        assertEquals(0, SqliteFixtures.count(directory, "SELECT count(*) FROM pragma_foreign_key_check"));
        try (SqliteDatabase database = new SqliteDatabase(directory)) {
            assertEquals("原消息", new ChatArchive(database).visibleRecord("alice", ConversationScope.group(groupId), first).content());
        }
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
