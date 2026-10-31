package cn.ltalk.client;

import cn.ltalk.common.*;
import cn.ltalk.server.ChatServer;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.*;
import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class ClientSessionsTest {
    @Test void failedCandidateAndCancellationPreserveOriginalAndSuccessfulLoginReplacesIt() throws Exception {
        Events events = new Events(); ClientSessions sessions = new ClientSessions(events);
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); Peer.seed(server);
            onEdt(() -> sessions.connectServer("127.0.0.1", server.port())); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.SERVER_CONNECTED);
            onEdt(() -> sessions.login("alice", "test123!")); assertEquals("alice", events.profiles.poll(5, TimeUnit.SECONDS).userId());
            try (Peer watcher = Peer.login(server.port(), "watcher")) {
                onEdt(sessions::beginSwitch); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.SERVER_CONNECTED);
                assertEquals(2, server.onlineCount());
                AtomicReference<Exception> sameAccount = new AtomicReference<>();
                onEdt(() -> { try { sessions.login("alice", "test123!"); } catch (Exception ex) { sameAccount.set(ex); } }); assertNotNull(sameAccount.get());
                onEdt(() -> sessions.login("other", "wrong!12")); assertEquals("LOGIN_FAILED", events.errors.poll(5, TimeUnit.SECONDS).code());
                watcher.privateTo("alice", "still online"); assertEquals("still online", events.messages.poll(5, TimeUnit.SECONDS).content());
                onEdt(sessions::cancelSwitch); awaitEdt(() -> server.connectionCount() == 2);
                onEdt(() -> { assertFalse(sessions.switching()); assertEquals(ChatClient.State.CONNECTED, sessions.state()); sessions.beginSwitch(); });
                awaitEdt(() -> sessions.authenticationState() == ChatClient.State.SERVER_CONNECTED);
                onEdt(() -> sessions.login("other", "test123!")); assertEquals("other", events.profiles.poll(5, TimeUnit.SECONDS).userId());
                awaitEdt(() -> !sessions.switching() && server.onlineCount() == 2 && server.connectionCount() == 2);
                onEdt(() -> sessions.sendPrivate("watcher", "new identity"));
                assertEquals("other", watcher.await(m -> m.type() == MessageType.PRIVATE_MESSAGE && "new identity".equals(m.content())).from());
                watcher.privateTo("alice", "gone"); watcher.error("USER_OFFLINE");
            }
        } finally { onEdt(sessions::close); }
    }
    @Test void fullServerRefusesCandidateWithoutDisconnectingOldAccountAndCloseReleasesBoth() throws Exception {
        Events events = new Events(); ClientSessions sessions = new ClientSessions(events);
        try (ChatServer server = new ChatServer("127.0.0.1", 0, 1, Duration.ofSeconds(10))) {
            server.start(); Peer.seed(server);
            onEdt(() -> sessions.connectServer("127.0.0.1", server.port())); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.SERVER_CONNECTED);
            onEdt(() -> sessions.login("alice", "test123!")); assertNotNull(events.profiles.poll(5, TimeUnit.SECONDS));
            onEdt(sessions::beginSwitch); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.DISCONNECTED);
            onEdt(() -> { assertEquals(ChatClient.State.CONNECTED, sessions.state()); sessions.cancelSwitch(); sessions.sendGroup("kept"); });
            assertEquals("kept", events.messages.poll(5, TimeUnit.SECONDS).content()); assertEquals(1, server.onlineCount());
            onEdt(sessions::close); awaitEdt(() -> server.connectionCount() == 0 && server.onlineCount() == 0);
        } finally { onEdt(sessions::close); }
    }
    @Test void sameUsernameOnAnotherServerIsAReplacementAndCancellationRestoresOriginalAddress() throws Exception {
        Events events = new Events(); ClientSessions sessions = new ClientSessions(events);
        try (ChatServer first = new ChatServer("127.0.0.1", 0); ChatServer second = new ChatServer("127.0.0.1", 0)) {
            first.start(); second.start();
            first.accounts().register("alice", "first", "pass!12", false); second.accounts().register("alice", "second", "pass!12", false);
            onEdt(() -> sessions.connectServer("127.0.0.1", first.port())); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.SERVER_CONNECTED);
            onEdt(() -> sessions.login("alice", "pass!12")); assertEquals("first", events.profiles.poll(5, TimeUnit.SECONDS).nickname());
            onEdt(sessions::beginSwitch); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.SERVER_CONNECTED);
            onEdt(sessions::disconnect); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.DISCONNECTED);
            onEdt(() -> sessions.connectServer("127.0.0.1", second.port())); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.SERVER_CONNECTED);
            onEdt(() -> { sessions.cancelSwitch(); assertEquals(first.port(), sessions.address().port()); assertEquals(0, events.replacements); sessions.beginSwitch(); });
            awaitEdt(() -> sessions.authenticationState() == ChatClient.State.SERVER_CONNECTED);
            onEdt(sessions::disconnect); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.DISCONNECTED);
            onEdt(() -> sessions.connectServer("127.0.0.1", second.port())); awaitEdt(() -> sessions.authenticationState() == ChatClient.State.SERVER_CONNECTED);
            onEdt(() -> sessions.login("alice", "pass!12")); assertEquals("second", events.profiles.poll(5, TimeUnit.SECONDS).nickname());
            onEdt(() -> { assertEquals(1, events.replacements); assertEquals(second.port(), sessions.address().port()); });
            awaitEdt(() -> first.onlineCount() == 0 && second.onlineCount() == 1);
        } finally { onEdt(sessions::close); }
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    private static void onEdt(Work work) throws Exception { SwingUtilities.invokeAndWait(() -> { try { work.run(); } catch (Exception ex) { throw new RuntimeException(ex); } }); }
    private static void awaitEdt(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6); boolean[] result = {false};
        do { onEdt(() -> result[0] = condition.getAsBoolean()); if (result[0]) return; Thread.sleep(10); } while (System.nanoTime() < end);
        fail("Condition did not become true");
    }
    private static final class Events implements ClientSessions.Listener {
        final BlockingQueue<UserProfile> profiles = new LinkedBlockingQueue<>();
        final BlockingQueue<Message> errors = new LinkedBlockingQueue<>(), messages = new LinkedBlockingQueue<>();
        int replacements;
        public void onProfile(UserProfile profile) { assertTrue(SwingUtilities.isEventDispatchThread()); profiles.add(profile); }
        public void onSwitchError(Message error) { errors.add(error); }
        public void onPrivate(Message message) { messages.add(message); }
        public void onGroup(Message message) { messages.add(message); }
        public void onSessionReplaced() { replacements++; }
    }
}
