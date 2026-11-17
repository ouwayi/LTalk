package cn.ltalk.support;

import cn.ltalk.common.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Real TCP peer; assertions observe frames rather than UI display. */
public final class Peer implements AutoCloseable {
    public final Socket socket;
    public final MessageCodec.FrameReader reader;
    public final List<Message> received = new ArrayList<>();
    private int sequence;

    public Peer(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(4000);
        socket.setTcpNoDelay(true);
        reader = new MessageCodec.FrameReader(socket.getInputStream());
    }
    public static Peer login(int port, String user) throws IOException {
        Peer peer = new Peer(port);
        try {
            peer.send(Message.of(MessageType.REGISTER).requestId("register").userId(user).nickname(user).password("test123!").build());
            Message registered = peer.read();
            assertTrue(registered.type() == MessageType.REGISTER_OK || "USER_EXISTS".equals(registered.code()));
            peer.send(Message.of(MessageType.LOGIN).requestId("login").userId(user).password("test123!").build());
            Message response = peer.read();
            assertNotNull(response);
            assertEquals(MessageType.LOGIN_OK, response.type(), () -> "First frame must be LOGIN_OK: " + response);
            return peer;
        } catch (Throwable ex) { peer.close(); throw ex; }
    }
    public static void seed(cn.ltalk.server.ChatServer server) throws IOException {
        for (String user : List.of("alice", "same", "other", "old_user", "new_user", "watcher", "bob")) {
            try { server.accounts().register(user, user, "test123!", false); }
            catch (cn.ltalk.common.ProtocolException ex) { if (!ex.code().equals("USER_EXISTS")) throw ex; }
        }
        cn.ltalk.server.SocialFixtures.permitPrivate(server, "alice", "bob");
    }
    public void send(Message message) throws IOException { MessageCodec.write(socket.getOutputStream(), message); }
    public void raw(String text) throws IOException { socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush(); }
    public void group(String content) throws IOException { send(Message.of(MessageType.GROUP_SEND).requestId("r" + ++sequence).content(content).build()); }
    public void privateTo(String target, String content) throws IOException {
        send(Message.of(MessageType.PRIVATE_SEND).requestId("r" + ++sequence).to(target).content(content).build());
    }
    public Message read() throws IOException {
        Message message = reader.read();
        if (message != null && message.type() == MessageType.PING)
            send(Message.of(MessageType.PONG).requestId(message.requestId()).build());
        if (message != null) received.add(message);
        return message;
    }
    public Message await(Predicate<Message> condition) throws IOException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            Message message = read();
            assertNotNull(message, "Unexpected EOF");
            if (condition.test(message)) return message;
        }
        throw new AssertionError("Expected event was not received");
    }
    public Message type(MessageType type) throws IOException { return await(m -> m.type() == type); }
    public Message users(int count) throws IOException { return await(m -> m.type() == MessageType.USER_LIST && m.users().size() == count); }
    public void error(String code) throws IOException { assertEquals(code, type(MessageType.ERROR).code()); }
    public void assertNo(Predicate<Message> forbidden, int millis) throws IOException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        try {
            while (System.nanoTime() < deadline) {
                socket.setSoTimeout(Math.max(1, (int) ((deadline - System.nanoTime()) / 1_000_000L)));
                Message message = read();
                if (message == null) return;
                assertFalse(forbidden.test(message), () -> "Unexpected frame: " + message);
            }
        } catch (SocketTimeoutException expected) {
            // A bounded quiet period checks that other parties receive no private frames.
        } finally { socket.setSoTimeout(4000); }
    }
    @Override public void close() throws IOException { socket.close(); }
}
