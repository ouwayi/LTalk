package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.support.Peer;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class PasswordChangeTest {
    @TempDir Path directory;

    @Test void passwordChangeRequiresCurrentPasswordAndOnlyUpdatesTheAuthenticatedAccount() throws Exception {
        Path file = directory.resolve("accounts").resolve(AccountStore.fileKey("alice") + ".json");
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory);
            server.start();
            try (Peer alice = Peer.login(server.port(), "alice");
                 Peer bob = Peer.login(server.port(), "bob"); Peer anonymous = new Peer(server.port())) {
                var original = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                anonymous.send(change("anonymous", null, "test123!", "demo-new!"));
                anonymous.error("NOT_AUTHENTICATED");
                alice.send(change("other", "bob", "test123!", "demo-new!"));
                alice.error("FORBIDDEN");
                alice.send(change("wrong", null, "incorrect!", "demo-new!"));
                alice.error("WRONG_PASSWORD");
                alice.send(change("short", null, "test123!", "123"));
                alice.error("INVALID_PASSWORD");
                assertEquals(original, JsonParser.parseString(Files.readString(file)));

                alice.send(change("save", null, "test123!", " demo-new! "));
                Message confirmed = alice.type(MessageType.PASSWORD_CHANGED);
                assertEquals("save", confirmed.requestId());
                assertNull(confirmed.password());
                assertNull(confirmed.newPassword());
                assertNull(confirmed.userId());
                var saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                assertNotEquals(original.get("salt"), saved.get("salt"));
                assertNotEquals(original.get("hash"), saved.get("hash"));
                for (String field : new String[]{"userId", "nickname", "avatar", "createdAt", "profileRevision"}) {
                    assertEquals(original.get(field), saved.get(field));
                }
                assertFalse(saved.toString().contains("demo-new!"));
                SocialFixtures.permitPrivate(server, "alice", "bob");
                alice.privateTo("bob", "连接继续可用");
                assertEquals("连接继续可用", bob.type(MessageType.PRIVATE_MESSAGE).content());
                assertFalse(bob.received.stream().anyMatch(m -> m.type() == MessageType.PASSWORD_CHANGED));
                assertEquals("bob", server.accounts().authenticate("bob", "test123!"));
            }
        }
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory);
            server.start();
            try (Peer alice = new Peer(server.port())) {
                alice.send(Message.of(MessageType.HELLO).requestId("hello").build());
                alice.type(MessageType.HELLO_OK);
                alice.send(login("test123!"));
                alice.error("LOGIN_FAILED");
                alice.send(login("demo-new!"));
                alice.error("LOGIN_FAILED");
                alice.send(login(" demo-new! "));
                assertEquals("alice", alice.type(MessageType.LOGIN_OK).userId());
            }
        }
    }

    private static Message change(String id, String user, String current, String next) {
        return Message.of(MessageType.PASSWORD_CHANGE).requestId(id).userId(user).password(current).newPassword(next).build();
    }

    private static Message login(String password) {
        return Message.of(MessageType.LOGIN).requestId("login").userId("alice").password(password).build();
    }
}
