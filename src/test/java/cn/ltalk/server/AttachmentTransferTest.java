package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class AttachmentTransferTest {
    @TempDir Path directory;

    @Test void cancelledAndOversizedUploadsDoNotPublishOrBlockChatAndTicketsCannotBeReused() throws Exception {
        try (ChatServer server = start(null); Peer alice = Peer.login(server.port(), "alice")) {
            alice.send(uploadRequest("oversized", null, Attachment.FILE_LIMIT + 1));
            alice.error("ATTACHMENT_TOO_LARGE");
            alice.send(uploadRequest("cancelled", null, 100_000));
            String token = alice.type(MessageType.ATTACH_TICKET).cursor();
            try (Transfer transfer = new Transfer(new Socket("127.0.0.1", server.port()), token)) {
                assertEquals(MessageType.ATTACH_READY, transfer.reader.read().type());
                transfer.socket.getOutputStream().write(new byte[140]);
                alice.group("聊天仍可发送");
                assertEquals("聊天仍可发送", alice.type(MessageType.GROUP_MESSAGE).content());
                alice.send(Message.of(MessageType.ATTACH_CANCEL).requestId("cancelled").build());
                assertNull(transfer.reader.read());
            }
            try (Transfer reused = new Transfer(new Socket("127.0.0.1", server.port()), token)) {
                assertEquals("FORBIDDEN", reused.reader.read().code());
            }
            assertEquals("ATTACHMENT_NOT_FOUND", downloadError(server, alice, ConversationScope.PUBLIC,
                    UUID.randomUUID().toString()));
            alice.assertNo(m -> m.type() == MessageType.GROUP_MESSAGE && m.attachment() != null, 120);
            try (var files = Files.list(directory.resolve("data/attachments"))) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test void downloadFollowsGroupHistoryAndPersonalDeletionAcrossServerRestart() throws Exception {
        String scope;
        byte[] bytes = new byte[48_171];
        new java.util.Random(123).nextBytes(bytes);
        String retained;
        try (ChatServer server = start(null); Peer alice = Peer.login(server.port(), "alice");
             Peer bob = Peer.login(server.port(), "bob"); Peer carol = Peer.login(server.port(), "carol")) {
            social(alice, SocialAction.GROUP_CREATE, b -> b.nickname("附件测试组").content("OPEN"));
            Message group = social(bob, SocialAction.FIND_GROUP, b -> b.content("附件测试组"));
            scope = ConversationScope.group(group.socialItem().group());
            social(bob, SocialAction.GROUP_REQUEST, b -> b.conversationId(scope));
            retained = upload(server, alice, scope, bytes);
            assertEquals(retained, bob.type(MessageType.GROUP_MESSAGE).messageId());
            String seenBeforeLeaving = upload(server, alice, scope, new byte[]{8, 10});
            assertEquals(seenBeforeLeaving, bob.type(MessageType.GROUP_MESSAGE).messageId());
            carol.assertNo(m -> retained.equals(m.messageId()), 80);
            assertEquals("FORBIDDEN", downloadError(server, carol, scope, retained));
            social(bob, SocialAction.GROUP_LEAVE, b -> b.conversationId(scope));
            String afterLeaving = upload(server, alice, scope, new byte[]{1, 3, 5});
            assertEquals("ATTACHMENT_NOT_FOUND", downloadError(server, bob, scope, afterLeaving));
            assertArrayEquals(bytes, download(server, bob, scope, retained));
            alice.send(Message.of(MessageType.MESSAGE_RECALL).requestId("recall-before-leave").conversationId(scope)
                    .messageId(seenBeforeLeaving).build());
            assertEquals(seenBeforeLeaving, alice.type(MessageType.MESSAGE_RECALLED).messageId());
            assertEquals(seenBeforeLeaving, bob.type(MessageType.MESSAGE_RECALLED).messageId());
            assertEquals("ATTACHMENT_NOT_FOUND", downloadError(server, bob, scope, seenBeforeLeaving));
            String revoked = upload(server, alice, scope, new byte[]{2, 4, 6});
            alice.send(Message.of(MessageType.MESSAGE_RECALL).requestId("recall-file").conversationId(scope).messageId(revoked).build());
            assertEquals(revoked, alice.type(MessageType.MESSAGE_RECALLED).messageId());
            bob.assertNo(m -> m.type() == MessageType.MESSAGE_RECALLED, 80);
            assertEquals("ATTACHMENT_NOT_FOUND", downloadError(server, alice, scope, revoked));
            bob.send(Message.of(MessageType.HISTORY_DELETE).requestId("delete").conversationId(scope).messageId(retained).build());
            bob.type(MessageType.HISTORY_DELETED);
            assertEquals("ATTACHMENT_NOT_FOUND", downloadError(server, bob, scope, retained));
        }
        try (ChatServer server = start(null); Peer alice = Peer.login(server.port(), "alice")) {
            assertArrayEquals(bytes, download(server, alice, scope, retained));
        }
    }

    @Test void transferOnTlsListenerUsesASecondVerifiedTlsSocket() throws Exception {
        TlsTestIdentity identity = new TlsTestIdentity(directory);
        try (ChatServer server = start(identity)) {
            server.accounts().register("alice", "Alice", "test123!", false);
            try (Socket rawChat = new Socket("127.0.0.1", server.port());
                 SSLSocket chat = TlsTransport.layer(rawChat, identity.client, "localhost", true)) {
                chat.setSoTimeout(5000);
                chat.startHandshake();
                MessageCodec.write(chat.getOutputStream(), Message.of(MessageType.HELLO).requestId("hello").build());
                var reader = new MessageCodec.FrameReader(chat.getInputStream());
                assertEquals(MessageType.HELLO_OK, reader.read().type());
                MessageCodec.write(chat.getOutputStream(), Message.of(MessageType.LOGIN).requestId("login")
                        .userId("alice").password("test123!").build());
                assertEquals(MessageType.LOGIN_OK, reader.read().type());
                MessageCodec.write(chat.getOutputStream(), uploadRequest("tls-file", null, 0));
                String token = await(reader, MessageType.ATTACH_TICKET).cursor();
                try (Socket rawFile = new Socket("127.0.0.1", server.port());
                     SSLSocket file = TlsTransport.layer(rawFile, identity.client, "localhost", true)) {
                    file.setSoTimeout(5000);
                    file.startHandshake();
                    try (Transfer transfer = new Transfer(file, token)) {
                        assertEquals(MessageType.ATTACH_READY, transfer.reader.read().type());
                        MessageCodec.write(file.getOutputStream(), Message.of(MessageType.ATTACH_COMPLETE).build());
                        Message done = transfer.reader.read();
                        assertEquals(MessageType.ATTACH_COMPLETE, done.type());
                        assertEquals(done.messageId(), await(reader, MessageType.GROUP_MESSAGE).messageId());
                    }
                }
            }
        }
    }

    @Test void offlineFriendGetsPrivateAttachmentWithoutExposingItInPublicHistory() throws Exception {
        try (ChatServer server = start(null); Peer alice = Peer.login(server.port(), "alice")) {
            server.accounts().register("bob", "Bob", "test123!", false);
            social(alice, SocialAction.FRIEND_REQUEST, b -> b.to("bob"));
            try (Peer bob = Peer.login(server.port(), "bob")) {
                Message request = social(bob, SocialAction.STATE, b -> { });
                assertEquals(SocialItem.Kind.FRIEND_IN, request.socialItem().kind());
                social(bob, SocialAction.FRIEND_ACCEPT, b -> b.messageId(request.socialItem().id()));
            }
            alice.users(2);
            alice.users(1);
            byte[] bytes = {0, 10, (byte) 0xff};
            alice.send(Message.of(MessageType.ATTACH_UPLOAD).requestId("offline").to("bob")
                    .attachment(new Attachment(Attachment.Kind.FILE, "数据.bin", bytes.length, null)).build());
            String id;
            try (Transfer transfer = open(server, alice)) {
                assertEquals(MessageType.ATTACH_READY, transfer.reader.read().type());
                transfer.socket.getOutputStream().write(bytes);
                MessageCodec.write(transfer.socket.getOutputStream(), Message.of(MessageType.ATTACH_COMPLETE).build());
                Message done = transfer.reader.read();
                assertEquals(MessageType.ATTACH_COMPLETE, done.type());
                id = done.messageId();
                assertEquals(id, alice.type(MessageType.PRIVATE_MESSAGE).messageId());
            }
            String scope = ConversationScope.privateChat("alice", "bob");
            try (Peer bob = Peer.login(server.port(), "bob"); Peer carol = Peer.login(server.port(), "carol")) {
                assertArrayEquals(bytes, download(server, bob, scope, id));
                assertEquals("ATTACHMENT_NOT_FOUND", downloadError(server, bob, ConversationScope.PUBLIC, id));
                requestDownload(carol, scope, id);
                carol.error("FORBIDDEN");
            }
        }
    }

    private ChatServer start(TlsTestIdentity identity) throws IOException {
        var server = new ChatServer("127.0.0.1", 0, 20, Duration.ofSeconds(10));
        if (identity != null) server.publicAccess(PublicAccess.configured(identity.server));
        server.dataDirectory(directory.resolve("data"));
        server.start();
        return server;
    }

    private static Message uploadRequest(String requestId, String scope, long size) {
        return Message.of(MessageType.ATTACH_UPLOAD).requestId(requestId).conversationId(scope)
                .attachment(new Attachment(Attachment.Kind.FILE, "二进制.bin", size, null)).build();
    }

    private static String upload(ChatServer server, Peer peer, String scope, byte[] bytes) throws Exception {
        peer.send(uploadRequest("upload", scope, bytes.length));
        try (Transfer transfer = open(server, peer)) {
            assertEquals(MessageType.ATTACH_READY, transfer.reader.read().type());
            transfer.socket.getOutputStream().write(bytes);
            MessageCodec.write(transfer.socket.getOutputStream(), Message.of(MessageType.ATTACH_COMPLETE).build());
            Message done = transfer.reader.read();
            assertEquals(MessageType.ATTACH_COMPLETE, done.type());
            assertEquals(done.messageId(), peer.type(MessageType.GROUP_MESSAGE).messageId());
            return done.messageId();
        }
    }

    private static byte[] download(ChatServer server, Peer peer, String scope, String id) throws Exception {
        requestDownload(peer, scope, id);
        try (Transfer transfer = open(server, peer)) {
            Message ready = transfer.reader.read();
            assertEquals(MessageType.ATTACH_READY, ready.type());
            byte[] bytes = transfer.input.readNBytes((int) ready.attachment().size());
            assertEquals(MessageType.ATTACH_COMPLETE, transfer.reader.read().type());
            return bytes;
        }
    }

    private static String downloadError(ChatServer server, Peer peer, String scope, String id) throws Exception {
        requestDownload(peer, scope, id);
        try (Transfer transfer = open(server, peer)) {
            Message rejected = transfer.reader.read();
            assertEquals(MessageType.ERROR, rejected.type());
            return rejected.code();
        }
    }

    private static void requestDownload(Peer peer, String scope, String id) throws IOException {
        peer.send(Message.of(MessageType.ATTACH_DOWNLOAD).requestId("download").conversationId(scope).messageId(id).build());
    }

    private static Transfer open(ChatServer server, Peer peer) throws IOException {
        String token = peer.type(MessageType.ATTACH_TICKET).cursor();
        return new Transfer(new Socket("127.0.0.1", server.port()), token);
    }

    private static Message social(Peer peer, SocialAction action, Consumer<Message.Builder> fields) throws IOException {
        String id = UUID.randomUUID().toString();
        var builder = Message.of(MessageType.SOCIAL_REQUEST).requestId(id).socialAction(action.name());
        fields.accept(builder);
        peer.send(builder.build());
        Message row = null;
        while (true) {
            Message event = peer.await(m -> id.equals(m.requestId()));
            if (event.type() == MessageType.SOCIAL_END) return row;
            assertEquals(MessageType.SOCIAL_ROW, event.type());
            row = event;
        }
    }

    private static Message await(MessageCodec.FrameReader reader, MessageType type) throws IOException {
        while (true) {
            Message event = reader.read();
            assertNotNull(event);
            assertNotEquals(MessageType.ERROR, event.type(), event.message());
            if (event.type() == type) return event;
        }
    }

    private static final class Transfer implements AutoCloseable {
        final Socket socket;
        final BufferedInputStream input;
        final MessageCodec.FrameReader reader;
        Transfer(Socket socket, String token) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(5000);
            input = new BufferedInputStream(socket.getInputStream());
            reader = new MessageCodec.FrameReader(input);
            MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.ATTACH_OPEN).cursor(token).build());
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
