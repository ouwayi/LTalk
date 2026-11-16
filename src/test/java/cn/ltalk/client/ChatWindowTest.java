package cn.ltalk.client;

import cn.ltalk.common.MessageType;
import cn.ltalk.common.Message;
import cn.ltalk.common.MessageCodec;
import cn.ltalk.client.ui.*;
import cn.ltalk.server.ChatServer;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises our own non-visible Swing component tree on the EDT, without OS input automation. */
class ChatWindowTest {
    @TempDir Path appearanceDirectory;

    @Test @Timeout(20)
    void oneDiscoveredServerConnectsWithoutLoggingInAndIsAvailableAfterRelaunch() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        InetAddress local = InetAddress.getByName("127.0.0.1");
        int udpPort;
        try (DatagramSocket probe = new DatagramSocket(0, local)) { udpPort = probe.getLocalPort(); }
        Path preferences = appearanceDirectory.resolve("appearance.properties");
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.enableDiscovery(udpPort, "本机测试服务器");
            server.start();
            onEdt(() -> ref.set(new ChatWindow(null, null, null, preferences,
                    () -> DiscoveryClient.discover(List.of(local), udpPort, 300))));
            ChatWindow first = ref.get();
            awaitEdt(() -> button(first, "connect").isEnabled());
            onEdt(() -> {
                assertEquals(Integer.toString(server.port()), text(first, "port").getText());
                assertTrue(text(first, "password").getText().isEmpty());
                assertEquals(0, server.onlineCount());
                first.dispatchEvent(new WindowEvent(first, WindowEvent.WINDOW_CLOSING));
                ref.set(new ChatWindow(null, null, null, preferences, List::of));
            });
            ChatWindow reopened = ref.get();
            awaitEdt(() -> label(reopened, "serverStatus").getText().contains("未找到"));
            onEdt(() -> {
                assertTrue(button(reopened, "connectServer").isEnabled());
                button(reopened, "recentServers").doClick();
            });
            awaitEdt(() -> serverChoices(reopened).getItemCount() == 1);
            onEdt(() -> {
                serverChoices(reopened).setSelectedIndex(0);
                assertEquals(Integer.toString(server.port()), text(reopened, "port").getText());
                assertFalse(button(reopened, "connect").isEnabled());
                button(reopened, "connectServer").doClick();
            });
            awaitEdt(() -> button(reopened, "connect").isEnabled());
        } finally { closeWindow(ref); }
    }

    @Test @Timeout(20)
    void multipleCandidatesRequireSelectionAndAnExplicitAddressSkipsDiscovery() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        Path preferences = appearanceDirectory.resolve("appearance.properties");
        try (ChatServer first = new ChatServer("127.0.0.1", 0); ChatServer second = new ChatServer("127.0.0.1", 0)) {
            first.start(); second.start();
            List<DiscoveryClient.Candidate> servers = List.of(
                    new DiscoveryClient.Candidate("开发聊天室", new ClientSessions.Address("127.0.0.1", first.port())),
                    new DiscoveryClient.Candidate("演示聊天室", new ClientSessions.Address("127.0.0.1", second.port())));
            onEdt(() -> ref.set(new ChatWindow(null, null, null, preferences, () -> servers)));
            ChatWindow window = ref.get();
            awaitEdt(() -> serverChoices(window).getItemCount() == 2);
            onEdt(() -> {
                assertEquals(0, first.connectionCount()); assertEquals(0, second.connectionCount());
                assertEquals(-1, serverChoices(window).getSelectedIndex());
                assertTrue(serverChoices(window).getItemAt(1).toString().contains("演示聊天室 · 127.0.0.1:" + second.port()));
                serverChoices(window).setSelectedIndex(1);
                assertFalse(button(window, "connect").isEnabled());
                assertEquals(Integer.toString(second.port()), text(window, "port").getText());
                window.applyAppearance(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK), false);
                render(window, 420, 760, "discovery-narrow.png");
                button(window, "connectServer").doClick();
            });
            awaitEdt(() -> button(window, "connect").isEnabled());
            assertEquals(0, first.connectionCount()); assertEquals(1, second.connectionCount());

            CountDownLatch scan = new CountDownLatch(1);
            onEdt(() -> {
                window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
                ref.set(new ChatWindow("127.0.0.1", Integer.toString(first.port()), "alice", preferences, () -> {
                    scan.countDown();
                    return servers;
                }));
            });
            ChatWindow explicit = ref.get();
            awaitEdt(() -> button(explicit, "connect").isEnabled());
            assertEquals(1, scan.getCount());
            assertEquals(1, first.connectionCount());
        } finally { closeWindow(ref); }
    }

    @Test @Timeout(15)
    void cancelAndManualInputPreventLateDiscoveryFromConnecting() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        Semaphore scans = new Semaphore(0);
        CountDownLatch release = new CountDownLatch(1);
        try (ChatServer manual = new ChatServer("127.0.0.1", 0); ChatServer discovered = new ChatServer("127.0.0.1", 0)) {
            manual.start(); discovered.start();
            onEdt(() -> ref.set(new ChatWindow(null, null, null, appearanceDirectory.resolve("appearance.properties"), () -> {
                scans.release();
                try { release.await(); }
                catch (InterruptedException ignored) { /* Simulates a result that is already being delivered. */ }
                return List.of(new DiscoveryClient.Candidate("另一台服务器", new ClientSessions.Address("127.0.0.1", discovered.port())));
            })));
            ChatWindow window = ref.get();
            assertTrue(scans.tryAcquire(4, TimeUnit.SECONDS));
            onEdt(() -> {
                button(window, "discoverServers").doClick();
                assertTrue(label(window, "serverStatus").getText().contains("查找已取消"));
                button(window, "discoverServers").doClick();
            });
            assertTrue(scans.tryAcquire(4, TimeUnit.SECONDS));
            onEdt(() -> {
                text(window, "port").setText(Integer.toString(manual.port()));
                assertTrue(label(window, "serverStatus").getText().contains("查找已取消"));
                button(window, "connectServer").doClick();
            });
            awaitEdt(() -> button(window, "connect").isEnabled());
            assertEquals(1, manual.connectionCount()); assertEquals(0, discovered.connectionCount());
        } finally {
            release.countDown();
            closeWindow(ref);
        }
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<ConnectionPanel.ServerChoice> serverChoices(ChatWindow window) {
        return (JComboBox<ConnectionPanel.ServerChoice>) find(window, "serverChoices");
    }

    private static void closeWindow(AtomicReference<ChatWindow> ref) throws Exception {
        if (ref.get() != null) onEdt(() -> ref.get().dispatchEvent(new WindowEvent(ref.get(), WindowEvent.WINDOW_CLOSING)));
    }

    @Test @Timeout(25)
    void sendFeedbackAndReconnectKeepConversationAndDrafts() throws Exception {
        AtomicReference<ChatWindow> windowRef = new AtomicReference<>();
        CountDownLatch reject = new CountDownLatch(1), drop = new CountDownLatch(1);
        ExecutorService fake = Executors.newSingleThreadExecutor();
        try (ServerSocket endpoint = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            endpoint.setSoTimeout(6000);
            Future<?> server = fake.submit(() -> {
                try (Socket socket = endpoint.accept()) {
                    MessageCodec.FrameReader reader = handshake(socket);
                    acceptLogin(socket, reader.read());
                    finishLoading(socket, reader);
                    Message rejected = reader.read();
                    assertEquals("被拒绝的消息", rejected.content());
                    assertTrue(reject.await(5, TimeUnit.SECONDS));
                    MessageCodec.write(socket.getOutputStream(), Message.error(rejected.requestId(), "USER_OFFLINE", "目标用户不在线"));
                    Message lost = reader.read();
                    assertEquals("断线时的消息", lost.content());
                    assertTrue(drop.await(5, TimeUnit.SECONDS));
                }
                try (Socket socket = endpoint.accept()) {
                    MessageCodec.FrameReader reader = handshake(socket);
                    Message wrong = reader.read();
                    assertEquals("alice", wrong.userId());
                    MessageCodec.write(socket.getOutputStream(), Message.error(wrong.requestId(), "LOGIN_FAILED", "用户名或密码错误"));
                    acceptLogin(socket, reader.read());
                    finishLoading(socket, reader);
                    Message sent = reader.read();
                    assertEquals(MessageType.PRIVATE_SEND, sent.type());
                    assertEquals("bob", sent.to());
                    assertEquals("私聊草稿", sent.content());
                    MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.PRIVATE_MESSAGE)
                            .requestId(sent.requestId()).messageId(UUID.randomUUID().toString())
                            .from("alice").to("bob").content(sent.content()).serverTime(Instant.now().toString()).build());
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });
            onEdt(() -> windowRef.set(new ChatWindow("127.0.0.1", Integer.toString(endpoint.getLocalPort()), "alice",
                    appearanceDirectory.resolve("appearance.properties"))));
            ChatWindow window = windowRef.get();
            awaitEdt(() -> button(window, "connect").isEnabled());
            onEdt(() -> { text(window, "password").setText("test123!"); button(window, "connect").doClick(); });
            awaitEdt(() -> button(window, "send").isEnabled() && list(window).getModel().getSize() == 2);
            onEdt(() -> {
                area(window, "input").setText("群聊草稿");
                selectPeer(window, "bob");
                area(window, "input").setText("被拒绝的消息");
                button(window, "send").doClick();
                assertTrue(hasSendStatus(window, "发送中"));
                assertEquals("", area(window, "input").getText());
                button(window, "group").doClick();
            });
            reject.countDown();
            onEdt(() -> selectPeer(window, "bob"));
            awaitEdt(() -> hasSendStatus(window, "发送失败"));
            onEdt(() -> {
                button(window, "recoverMessage").doClick();
                assertEquals("被拒绝的消息", area(window, "input").getText());
                area(window, "input").setText("断线时的消息");
                button(window, "send").doClick();
                area(window, "input").setText("私聊草稿");
            });
            drop.countDown();
            awaitEdt(() -> !button(window, "send").isEnabled() && hasSendStatus(window, "结果待确认"));
            onEdt(() -> {
                assertFalse(find(window, "welcomeScroll").isVisible());
                assertTrue(label(window, "recoveryStatus").getText().contains("断开"));
                assertTrue(label(window, "recipient").getText().contains("暂未连接"));
                assertEquals("私聊草稿", area(window, "input").getText());
                window.applyAppearance(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK), false);
                render(window, 360, 580, "chat-reconnect-narrow.png");
                JButton recovery = button(window, "reconnect");
                assertTrue(recovery.getWidth() > 0 && recovery.isEnabled());
                recovery.doClick();
                assertEquals("127.0.0.1", text(window, "host").getText());
                assertEquals(Integer.toString(endpoint.getLocalPort()), text(window, "port").getText());
                assertEquals("alice", text(window, "userId").getText());
                assertEquals(0, ((JPasswordField) text(window, "password")).getPassword().length);
            });
            awaitEdt(() -> button(window, "connect").isEnabled());
            onEdt(() -> {
                button(window, "backToChat").doClick();
                assertEquals("私聊草稿", area(window, "input").getText());
                assertFalse(button(window, "send").isEnabled());
                assertEquals("继续登录", button(window, "reconnect").getText());
                button(window, "reconnect").doClick();
            });
            onEdt(() -> { text(window, "password").setText("wrong!"); button(window, "connect").doClick(); });
            awaitEdt(() -> label(window, "authFeedback").getText().contains("LOGIN_FAILED"));
            onEdt(() -> { text(window, "password").setText("test123!"); button(window, "connect").doClick(); });
            awaitEdt(() -> button(window, "send").isEnabled());
            onEdt(() -> {
                assertTrue(label(window, "recipient").getText().contains("私聊 bob"));
                assertTrue(hasSendStatus(window, "发送失败"));
                assertTrue(hasSendStatus(window, "结果待确认"));
                assertEquals("私聊草稿", area(window, "input").getText());
                button(window, "group").doClick();
                assertEquals("群聊草稿", area(window, "input").getText());
                selectPeer(window, "bob");
                button(window, "send").doClick();
            });
            awaitEdt(() -> hasSendStatus(window, "已确认"));
            onEdt(() -> {
                assertEquals(1, history(window).split("私聊草稿", -1).length - 1);
                window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
            });
            server.get(5, TimeUnit.SECONDS);
        } finally {
            if (windowRef.get() != null && windowRef.get().isDisplayable())
                onEdt(() -> windowRef.get().dispatchEvent(new WindowEvent(windowRef.get(), WindowEvent.WINDOW_CLOSING)));
            fake.shutdownNow();
        }
    }

    private static MessageCodec.FrameReader handshake(Socket socket) throws Exception {
        socket.setSoTimeout(6000);
        MessageCodec.FrameReader reader = new MessageCodec.FrameReader(socket.getInputStream());
        Message hello = reader.read();
        assertEquals(MessageType.HELLO, hello.type());
        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.HELLO_OK).requestId(hello.requestId()).build());
        return reader;
    }

    private static void acceptLogin(Socket socket, Message login) throws Exception {
        assertEquals(MessageType.LOGIN, login.type());
        assertEquals("alice", login.userId());
        assertEquals("test123!", login.password());
        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.LOGIN_OK).requestId(login.requestId())
                .userId("alice").nickname("Alice").build());
    }

    private static void finishLoading(Socket socket, MessageCodec.FrameReader reader) throws Exception {
        Message history = reader.read();
        assertEquals(MessageType.HISTORY_REQUEST, history.type());
        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.HISTORY_END).requestId(history.requestId()).build());
        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.USER_LIST).revision(1L).users(List.of("alice", "bob")).build());
    }

    private static boolean hasSendStatus(Container root, String status) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label && "sendStatus".equals(label.getName()) && label.getText().endsWith(status)) return true;
            if (component instanceof Container nested && hasSendStatus(nested, status)) return true;
        }
        return false;
    }

    @Test @Timeout(20)
    void connectionPrivateTargetLossDraftAndGroupEcho() throws Exception {
        AtomicReference<ChatWindow> windowRef = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); cn.ltalk.support.Peer.seed(server);
            try (Peer bob = Peer.login(server.port(), "bob")) {
                SwingUtilities.invokeAndWait(() -> windowRef.set(new ChatWindow(null, Integer.toString(server.port()), "alice", appearanceDirectory.resolve("appearance.properties"))));
                ChatWindow window = windowRef.get();
                onEdt(() -> {
                    assertFalse(button(window, "send").isEnabled());
                    text(window, "port").setText("wrong"); button(window, "connectServer").doClick();
                    assertTrue(label(window, "status").getText().contains("端口"));
                    text(window, "port").setText(Integer.toString(server.port())); button(window, "connectServer").doClick();
                });
                awaitEdt(() -> button(window, "connect").isEnabled());
                onEdt(() -> { text(window, "password").setText("test123!"); button(window, "connect").doClick(); });
                awaitEdt(() -> button(window, "send").isEnabled() && list(window).getModel().getSize() == 2);
                onEdt(() -> {
                    assertFalse(text(window, "host").isEnabled());
                    selectPeer(window, "bob");
                    assertTrue(label(window, "recipient").getText().contains("私聊 bob"));
                    area(window, "input").setText("UI-P-001"); button(window, "send").doClick();
                    assertEquals("", area(window, "input").getText());
                });
                assertEquals("UI-P-001", bob.type(MessageType.PRIVATE_MESSAGE).content());
                awaitEdt(() -> history(window).contains("UI-P-001"));
                onEdt(() -> area(window, "input").setText("保留的私聊草稿"));
                bob.close();
                awaitEdt(() -> !button(window, "send").isEnabled() && label(window, "recipient").getText().contains("重新选择"));
                onEdt(() -> {
                    assertEquals("保留的私聊草稿", area(window, "input").getText());
                    assertTrue(label(window, "recipient").getText().contains("重新选择"));
                    button(window, "group").doClick(); assertTrue(button(window, "send").isEnabled());
                    area(window, "input").setText("UI-G-001 中文🙂\n<html><b>纯文本</b>"); button(window, "send").doClick();
                });
                awaitEdt(() -> history(window).contains("UI-G-001"));
                onEdt(() -> {
                    String history = history(window);
                    assertEquals(1, history.split("UI-G-001", -1).length - 1);
                    assertTrue(history.contains("<html><b>纯文本</b>"));
                    assertFalse(history.contains("UI-P-001"), "Private history must not leak into the public conversation");
                    selectPeer(window, "bob"); assertTrue(history(window).contains("UI-P-001"));
                    assertTrue(label(window, "recipient").getText().contains("私聊 bob"));
                    button(window, "group").doClick();
                    renderComponentPreview(window);
                    button(window, "disconnect").doClick();
                });
                awaitEdt(() -> button(window, "connectServer").isEnabled());
                onEdt(() -> { assertFalse(button(window, "send").isEnabled()); assertTrue(text(window, "host").isEnabled()); });
            }
        } finally {
            if (windowRef.get() != null) onEdt(() -> windowRef.get().dispatchEvent(new WindowEvent(windowRef.get(), WindowEvent.WINDOW_CLOSING)));
        }
    }

    @Test @Timeout(25)
    void unreadSearchDraftKeyboardAndNarrowNavigationUseExistingNetworkApi() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); cn.ltalk.support.Peer.seed(server);
            try (Peer bob = Peer.login(server.port(), "bob"); Peer carol = Peer.login(server.port(), "carol")) {
                onEdt(() -> { ChatTheme.install(); ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice", appearanceDirectory.resolve("appearance.properties"))); });
                ChatWindow window = ref.get(); login(window);
                awaitEdt(() -> button(window, "send").isEnabled() && list(window).getModel().getSize() == 3);
                bob.group("你好，欢迎来到 LTalk 👋");
                awaitEdt(() -> history(window).contains("欢迎来到"));
                bob.privateTo("alice", "私聊未读消息");
                awaitEdt(() -> summary(window, "bob").unread() == 1);
                onEdt(() -> {
                    assertFalse(history(window).contains("私聊未读消息"));
                    area(window, "input").setText("公共草稿"); selectPeer(window, "bob");
                    assertEquals(0, summary(window, "bob").unread()); assertTrue(history(window).contains("私聊未读消息"));
                    area(window, "input").setText("第一行");
                    JTextArea editor = area(window, "input"); editor.setCaretPosition(editor.getDocument().getLength());
                    invokeKey(editor, java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK);
                    editor.append("第二行"); assertEquals("第一行\n第二行", editor.getText());
                    button(window, "group").doClick(); assertEquals("公共草稿", editor.getText());
                    selectPeer(window, "bob"); assertEquals("第一行\n第二行", editor.getText());
                    invokeKey(editor, java.awt.event.KeyEvent.VK_ENTER, 0); assertEquals("", editor.getText());
                });
                assertEquals("第一行\n第二行", bob.await(m -> m.type() == MessageType.PRIVATE_MESSAGE && "alice".equals(m.from()) && "bob".equals(m.to())).content());
                awaitEdt(() -> history(window).contains("第二行"));
                onEdt(() -> {
                    text(window, "conversationSearch").setText("no-such-person"); assertEquals(0, list(window).getModel().getSize());
                    assertEquals("bob", label(window, "conversationTitle").getText(), "Filtering must not change the send target");
                    text(window, "conversationSearch").setText("BOB");
                    assertEquals(2, list(window).getModel().getSize(), "Matches bob's private conversation and the public preview sent by bob");
                    assertEquals("bob", summary(window, "bob").title());
                    text(window, "conversationSearch").setText(""); button(window, "group").doClick();
                    area(window, "input").setText("很高兴见到大家，我们可以开始讨论项目了。"); button(window, "send").doClick();
                });
                awaitEdt(() -> history(window).contains("讨论项目"));
                carol.group("群聊消息会显示在这里，私聊记录则保留在各自的会话里。");
                carol.privateTo("alice", "记得看一下今天的演示安排。");
                awaitEdt(() -> summary(window, "carol").unread() == 1 && history(window).contains("私聊记录"));
                onEdt(() -> {
                    render(window, 1200, 780, "ui-desktop.png");
                    render(window, 420, 760, "ui-narrow-chat.png");
                    assertTrue(find(window, "back").isVisible()); assertFalse(find(window, "sidebar").isVisible());
                    button(window, "attach").doClick(); assertTrue(label(window, "status").getText().contains("尚未接入"));
                    assertTrue(label(window, "composerHint").getText().contains("尚未接入"));
                    button(window, "emoji").doClick(); assertTrue(label(window, "status").getText().contains("表情"));
                    assertTrue(label(window, "composerHint").getText().contains("表情"), "Narrow chat must show feedback outside the hidden sidebar");
                    button(window, "back").doClick(); layout(window.getContentPane());
                    assertTrue(find(window, "sidebar").isVisible()); assertFalse(find(window, "chatPane").isVisible());
                    render(window, 420, 760, "ui-narrow-list.png");
                    button(window, "group").doClick(); layout(window.getContentPane()); assertTrue(find(window, "chatPane").isVisible());
                    assertTrue(history(window).contains("讨论项目"));
                    text(window, "messageSearch").setText("欢迎"); assertTrue(history(window).contains("欢迎")); assertFalse(history(window).contains("讨论项目"));
                    text(window, "messageSearch").setText("");
                    area(window, "input").setText("\n "); button(window, "send").doClick();
                    assertEquals("\n ", area(window, "input").getText()); assertTrue(label(window, "status").getText().contains("空白"));
                });
                carol.assertNo(m -> m.type() == MessageType.PRIVATE_MESSAGE
                        && ("bob".equals(m.from()) || "bob".equals(m.to())), 100);
            }
        } finally { if (ref.get() != null) onEdt(() -> ref.get().dispatchEvent(new WindowEvent(ref.get(), WindowEvent.WINDOW_CLOSING))); }
    }

    @Test @Timeout(25)
    void appearanceUpdatesExistingMessagesKeepsDraftAndConnectionAndSurvivesRestart() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>(); Path settings = appearanceDirectory.resolve("appearance.properties");
        var dark = AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK).withBackground(AppearanceSettings.BackgroundStyle.SOLID,
                AppearanceSettings.color("#182A3D"), AppearanceSettings.color("#314860"));
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); cn.ltalk.support.Peer.seed(server);
            try (Peer bob = Peer.login(server.port(), "bob")) {
                onEdt(() -> { ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice", settings)); });
                ChatWindow window = ref.get(); login(window); awaitEdt(() -> button(window, "send").isEnabled());
                bob.group("切换主题之前的消息"); awaitEdt(() -> history(window).contains("之前的消息"));
                onEdt(() -> {
                    area(window, "input").setText("不应丢失的草稿"); window.applyAppearance(dark, true);
                    assertEquals("不应丢失的草稿", area(window, "input").getText()); assertTrue(button(window, "send").isEnabled());
                    assertTrue(ChatTheme.palette(window.getContentPane()).dark());
                    assertEquals(ThemePalette.of(dark).surface(), find(window, "sidebar").getBackground());
                    assertEquals(ThemePalette.of(dark).text(), area(window, "input").getForeground());
                    assertTrue(history(window).contains("之前的消息")); assertFalse(text(window, "host").isEnabled());
                    render(window, 1200, 780, "ui-dark-desktop.png");
                    button(window, "group").doClick(); render(window, 420, 760, "ui-dark-narrow.png");
                    area(window, "input").setText("DARK-G-001"); button(window, "send").doClick();
                });
                assertEquals("DARK-G-001", bob.await(m -> m.type() == MessageType.GROUP_MESSAGE && "alice".equals(m.from())).content());
                awaitEdt(() -> history(window).contains("DARK-G-001"));
                onEdt(() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING))); ref.set(null);
                onEdt(() -> {
                    ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice", settings));
                    assertTrue(ChatTheme.palette(ref.get().getContentPane()).dark());
                    assertEquals(dark, new AppearancePreferences(settings).load());
                });
            }
        } finally { if (ref.get() != null) onEdt(() -> ref.get().dispatchEvent(new WindowEvent(ref.get(), WindowEvent.WINDOW_CLOSING))); }
    }

    @Test void preferenceSaveFailureIsVisibleWithoutUndoingAppearanceOrDraft() throws Exception {
        Path invalidTarget = Files.createDirectory(appearanceDirectory.resolve("cannot-replace-directory"));
        onEdt(() -> {
            ChatWindow window = new ChatWindow(null, null, null, invalidTarget);
            try {
                area(window, "input").setText("保存失败也保留草稿");
                window.applyAppearance(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK), true);
                assertTrue(ChatTheme.palette(window.getContentPane()).dark());
                assertEquals("保存失败也保留草稿", area(window, "input").getText());
                assertTrue(label(window, "status").getText().contains("保存失败"));
            } finally { window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)); }
        });
    }

    @Test @Timeout(25)
    void startupRegistrationProfileEditingAndAccountSwitchUseDedicatedPage() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); Peer.seed(server);
            onEdt(() -> {
                ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), null, appearanceDirectory.resolve("new-ui.properties")));
                ChatWindow window = ref.get();
                assertTrue(find(window, "welcomeScroll").isVisible()); assertFalse(button(window, "send").isEnabled());
                assertEquals("未登录", label(window, "identityNickname").getText());
                assertNull(find(window, "connectionSettings"));
                assertNull(find(window.mainMenu(), "menuExport")); assertNotNull(find(window.mainMenu(), "menuAppearance"));
                assertNull(find(window.conversationMenu(), "chatAppearance")); assertNull(find(window.conversationMenu(), "chatConnection"));
                assertFalse(((JMenuItem) find(window.conversationMenu(), "chatExport")).isEnabled());
                render(window, 1000, 760, "welcome-login.png");
            });
            ChatWindow window = ref.get(); awaitEdt(() -> button(window, "connect").isEnabled());
            onEdt(() -> {
                assertTrue(text(window, "password").isEnabled()); button(window, "register").doClick();
                assertTrue(text(window, "nickname").getParent().isVisible());
                text(window, "userId").setText("newUI"); text(window, "nickname").setText("新用户");
                text(window, "password").setText("pass!12"); text(window, "passwordConfirmation").setText("wrong!12");
                button(window, "connect").doClick(); assertTrue(label(window, "authFeedback").getText().contains("不一致"));
                text(window, "passwordConfirmation").setText("pass!12");
                render(window, 1000, 760, "welcome-register.png"); render(window, 420, 700, "welcome-narrow.png");
                JScrollPane scroll = (JScrollPane) find(window, "welcomeScroll");
                assertTrue(scroll.getViewport().getViewSize().height > scroll.getViewport().getExtentSize().height);
                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                render(window, 420, 700, "welcome-narrow-bottom.png");
                assertTrue(scroll.getViewport().getViewRect().intersects(SwingUtilities.convertRectangle(button(window, "connect").getParent(), button(window, "connect").getBounds(), (Container) scroll.getViewport().getView())));
                window.applyAppearance(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK), false);
                assertEquals(ThemePalette.of(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK)).text(), find(window, "randomUsername").getForeground());
                render(window, 1000, 760, "welcome-dark.png"); window.applyAppearance(AppearanceSettings.defaults(), false);
                button(window, "connect").doClick();
            });
            awaitEdt(() -> button(window, "send").isEnabled());
            onEdt(() -> {
                assertFalse(find(window, "welcomeScroll").isVisible()); assertEquals("新用户", label(window, "identityNickname").getText());
                assertEquals("@newUI", label(window, "identityUsername").getText()); assertEquals("", text(window, "password").getText());
                assertTrue(((JMenuItem) find(window.conversationMenu(), "chatExport")).isEnabled());
                ((JMenuItem) find(window.identityMenu(), "accountProfile")).doClick();
                JDialog dialog = profileDialog(window); assertNotNull(dialog);
                renderContainer(dialog.getContentPane(), 480, 570, "profile-editor.png");
                ((JTextField) find(dialog, "profileNickname")).setText("修改后的昵称");
                ((JButton) find(dialog, "profileSave")).doClick();
            });
            awaitEdt(() -> "修改后的昵称".equals(label(window, "identityNickname").getText()) && profileDialog(window) == null);
            assertEquals("修改后的昵称", server.accounts().profile("newUI").nickname());
            onEdt(() -> {
                button(window, "group").doClick(); area(window, "input").setText("上一账号的草稿");
                render(window, 1000, 760, "profile-footer.png");
                ((JMenuItem) find(window.identityMenu(), "accountLogout")).doClick();
                assertFalse(button(window, "send").isEnabled()); assertEquals("", area(window, "input").getText());
                assertEquals("newUI", text(window, "userId").getText());
            });
            awaitEdt(() -> button(window, "connect").isEnabled() && server.onlineCount() == 0);
            onEdt(() -> { text(window, "password").setText("pass!12"); button(window, "connect").doClick(); });
            awaitEdt(() -> button(window, "send").isEnabled());
            onEdt(() -> {
                ((JMenuItem) find(window.identityMenu(), "accountSwitch")).doClick();
                assertEquals("", text(window, "userId").getText()); assertEquals("", text(window, "password").getText());
                assertEquals("修改后的昵称", label(window, "identityNickname").getText());
                assertTrue(button(window, "backToChat").isVisible()); assertEquals(1, server.onlineCount());
            });
            awaitEdt(() -> button(window, "connect").isEnabled() && server.onlineCount() == 1);
            onEdt(() -> { text(window, "userId").setText("other"); text(window, "password").setText("test123!"); button(window, "connect").doClick(); });
            awaitEdt(() -> "@other".equals(label(window, "identityUsername").getText()) && !find(window, "welcomeScroll").isVisible());
            onEdt(() -> { assertEquals("@other", label(window, "identityUsername").getText()); assertEquals("", area(window, "input").getText()); });
        } finally { if (ref.get() != null) onEdt(() -> ref.get().dispatchEvent(new WindowEvent(ref.get(), WindowEvent.WINDOW_CLOSING))); }
    }
    private static JDialog profileDialog(ChatWindow window) {
        for (Window child : window.getOwnedWindows()) if (child instanceof JDialog dialog && "profileDialog".equals(dialog.getName()) && dialog.isDisplayable()) return dialog;
        return null;
    }

    @Test @Timeout(25)
    void passwordFormValidatesConfirmationAndReportsServerResultWithoutLeavingChat() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            server.accounts().register("alice", "测试账号", "test123!", false);
            onEdt(() -> ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice",
                    appearanceDirectory.resolve("password.properties"))));
            ChatWindow window = ref.get();
            login(window);
            awaitEdt(() -> label(window, "status").getText().contains("已加载"));
            AtomicReference<JDialog> dialogRef = new AtomicReference<>();
            onEdt(() -> {
                area(window, "input").setText("保留这条草稿");
                ((JMenuItem) find(window.identityMenu(), "accountProfile")).doClick();
                JDialog profile = profileDialog(window);
                ((JButton) find(profile, "profileChangePassword")).doClick();
                for (Window owned : profile.getOwnedWindows()) {
                    if (owned instanceof JDialog dialog && "passwordChangeDialog".equals(dialog.getName())
                            && dialog.isDisplayable()) dialogRef.set(dialog);
                }
                JDialog dialog = dialogRef.get();
                assertNotNull(dialog);
                renderContainer(dialog.getContentPane(), 410, 350, "password-change.png");
                fillPasswordChange(dialog, "test123!", "demo-new!", "different!");
                JButton save = (JButton) find(dialog, "submitPasswordChange");
                save.doClick();
                assertTrue(((JLabel) find(dialog, "passwordChangeFeedback")).getText().contains("不一致"));
                fillPasswordChange(dialog, "incorrect!", "demo-new!", "demo-new!");
                ((JToggleButton) find(dialog, "showNewPassword")).doClick();
                save.doClick();
                assertFalse(save.isEnabled());
                JPasswordField next = (JPasswordField) find(dialog, "newPassword");
                assertEquals(0, next.getPassword().length);
                assertTrue(next.echoCharIsSet());
            });
            JDialog dialog = dialogRef.get();
            awaitEdt(() -> ((JLabel) find(dialog, "passwordChangeFeedback")).getText().contains("当前密码不正确"));
            onEdt(() -> {
                fillPasswordChange(dialog, "test123!", "demo-new!", "demo-new!");
                ((JButton) find(dialog, "submitPasswordChange")).doClick();
            });
            awaitEdt(() -> !dialog.isDisplayable() && label(window, "status").getText().contains("密码已修改"));
            onEdt(() -> {
                assertTrue(button(window, "send").isEnabled());
                assertEquals("保留这条草稿", area(window, "input").getText());
                assertNotNull(profileDialog(window));
            });
            assertEquals("alice", server.accounts().authenticate("alice", "demo-new!"));
        } finally {
            if (ref.get() != null) onEdt(() -> ref.get().dispatchEvent(new WindowEvent(ref.get(), WindowEvent.WINDOW_CLOSING)));
        }
    }

    private static void fillPasswordChange(JDialog dialog, String current, String next, String confirmation) {
        ((JPasswordField) find(dialog, "currentPassword")).setText(current);
        ((JPasswordField) find(dialog, "newPassword")).setText(next);
        ((JPasswordField) find(dialog, "confirmNewPassword")).setText(confirmation);
    }

    @Test @Timeout(30)
    void accountSwitchKeepsOldChatThroughFailureAndCancelAndOnlyClearsItAfterSuccess() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); Peer.seed(server);
            try (Peer bob = Peer.login(server.port(), "bob")) {
                onEdt(() -> ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice", appearanceDirectory.resolve("switch.properties"))));
                ChatWindow window = ref.get(); login(window); awaitEdt(() -> button(window, "send").isEnabled());
                onEdt(() -> {
                    button(window, "group").doClick(); area(window, "input").setText("原账号的草稿");
                    ((JMenuItem) find(window.identityMenu(), "accountSwitch")).doClick();
                    assertTrue(button(window, "backToChat").isVisible()); assertEquals("@alice", label(window, "identityUsername").getText());
                });
                awaitEdt(() -> button(window, "connect").isEnabled()); assertEquals(2, server.onlineCount());
                bob.group("在切换页继续收到的消息"); awaitEdt(() -> history(window).contains("切换页继续收到"));
                bob.privateTo("alice", "切换期间仍收到的私聊");
                awaitEdt(() -> summary(window, "bob").preview().contains("切换期间仍收到的私聊"));
                onEdt(() -> { text(window, "userId").setText("other"); text(window, "password").setText("wrong!12"); button(window, "connect").doClick(); });
                awaitEdt(() -> label(window, "authFeedback").getText().contains("LOGIN_FAILED"));
                onEdt(() -> {
                    assertEquals("@alice", label(window, "identityUsername").getText()); button(window, "backToChat").doClick();
                    assertFalse(find(window, "welcomeScroll").isVisible()); assertEquals("原账号的草稿", area(window, "input").getText());
                    assertTrue(history(window).contains("切换页继续收到")); assertEquals("", text(window, "password").getText());
                    ((JMenuItem) find(window.identityMenu(), "accountSwitch")).doClick();
                });
                awaitEdt(() -> button(window, "connect").isEnabled());
                onEdt(() -> {
                    text(window, "userId").setText("other"); text(window, "password").setText("test123!"); button(window, "connect").doClick();
                    button(window, "backToChat").doClick(); // Cancel even while authentication is in flight.
                    assertEquals("@alice", label(window, "identityUsername").getText());
                    button(window, "send").doClick();
                });
                assertEquals("alice", bob.await(m -> m.type() == MessageType.GROUP_MESSAGE && "原账号的草稿".equals(m.content())).from());
                awaitEdt(() -> server.onlineCount() == 2 && server.connectionCount() == 2);
                onEdt(() -> ((JMenuItem) find(window.identityMenu(), "accountSwitch")).doClick());
                awaitEdt(() -> button(window, "connect").isEnabled());
                onEdt(() -> {
                    area(window, "input").setText("成功切换才清除这条草稿");
                    text(window, "userId").setText("other"); text(window, "password").setText("test123!"); button(window, "connect").doClick();
                    assertEquals("@alice", label(window, "identityUsername").getText());
                });
                awaitEdt(() -> "@other".equals(label(window, "identityUsername").getText()) && !find(window, "welcomeScroll").isVisible());
                awaitEdt(() -> server.onlineCount() == 2 && server.connectionCount() == 2);
                awaitEdt(() -> history(window).contains("切换页继续收到") && label(window, "status").getText().contains("已加载"));
                onEdt(() -> {
                    assertEquals("", area(window, "input").getText());
                    assertFalse(summary(window, "bob").preview().contains("切换期间仍收到的私聊"));
                    assertEquals("", text(window, "password").getText()); render(window, 1000, 760, "account-switch-success.png");
                });
                bob.privateTo("alice", "old session gone"); bob.error("USER_OFFLINE");
            }
        } finally { if (ref.get() != null) onEdt(() -> ref.get().dispatchEvent(new WindowEvent(ref.get(), WindowEvent.WINDOW_CLOSING))); }
    }

    private static void login(ChatWindow window) throws Exception {
        awaitEdt(() -> button(window, "connect").isEnabled());
        onEdt(() -> { text(window, "password").setText("test123!"); button(window, "connect").doClick(); });
    }
    @Test @Timeout(35)
    void loginRestoresPreviousMessagesAndOfflinePrivateChatAndDeletionPersists() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1",0)) {
            server.start(); Peer.seed(server);
            try (Peer alice = Peer.login(server.port(),"alice"); Peer bob = Peer.login(server.port(),"bob")) {
                alice.group("历史公共一"); alice.type(MessageType.GROUP_MESSAGE); bob.type(MessageType.GROUP_MESSAGE);
                bob.privateTo("alice","离线联系人历史"); alice.type(MessageType.PRIVATE_MESSAGE); bob.type(MessageType.PRIVATE_MESSAGE);
                alice.close();
                bob.users(1);
                bob.group("历史公共二"); bob.type(MessageType.GROUP_MESSAGE);
            }
            awaitEdt(() -> server.onlineCount()==0);
            BufferedImage picture = new BufferedImage(144, 144, BufferedImage.TYPE_INT_RGB);
            Graphics2D avatarGraphics = picture.createGraphics();
            Color avatarColor = new Color(190, 32, 125);
            avatarGraphics.setColor(avatarColor); avatarGraphics.fillRect(0, 0, 144, 144); avatarGraphics.dispose();
            Path image = appearanceDirectory.resolve("avatar.png"); ImageIO.write(picture, "png", image.toFile());
            String avatar = AvatarImporter.load(image);
            server.accounts().updateProfile("bob", "离线昵称", avatar);
            onEdt(() -> ref.set(new ChatWindow("127.0.0.1",Integer.toString(server.port()),"alice",appearanceDirectory.resolve("history.properties"))));
            ChatWindow window=ref.get(); login(window);
            awaitEdt(() -> history(window).contains("历史公共一") && history(window).contains("[离线昵称] 历史公共二") && label(window,"status").getText().contains("已加载"));
            onEdt(() -> {
                assertEquals("离线昵称：历史公共二", summary(window, ConversationId.PUBLIC).preview());
                assertEquals("离线昵称", summary(window, "bob").title());
                BufferedImage receivedAvatar = summary(window, "bob").profile().avatar();
                assertEquals(256, receivedAvatar.getWidth());
                assertEquals(avatarColor.getRGB(), receivedAvatar.getRGB(128, 128));
                selectPeer(window,"bob"); assertTrue(history(window).contains("[离线昵称] 离线联系人历史")); assertFalse(button(window,"send").isEnabled());
                assertEquals("离线昵称", label(window, "conversationTitle").getText());
                assertEquals(avatarColor.getRGB(), iconCenter(label(window, "conversationAvatar")));
                button(window,"group").doClick(); area(window,"input").setText("删除不影响草稿");
                render(window,1000,760,"history-restored.png");
                JMenuItem item=messageDelete(window,"历史公共一"); assertNotNull(item); assertTrue(item.isEnabled());
                confirmNext(window); item.doClick();
            });
            awaitEdt(() -> !history(window).contains("历史公共一") && history(window).contains("历史公共二") && label(window,"status").getText().contains("已加载"));
            onEdt(() -> {
                assertEquals("删除不影响草稿",area(window,"input").getText()); confirmNext(window);
                ((JMenuItem)find(window.conversationMenu(),"chatClearHistory")).doClick();
            });
            awaitEdt(() -> !history(window).contains("历史公共二") && label(window,"status").getText().contains("已加载"));
            onEdt(() -> window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING))); ref.set(null); awaitEdt(() -> server.onlineCount()==0);
            onEdt(() -> ref.set(new ChatWindow("127.0.0.1",Integer.toString(server.port()),"alice",appearanceDirectory.resolve("history.properties"))));
            ChatWindow reopened=ref.get(); login(reopened);
            awaitEdt(() -> label(reopened,"status").getText().contains("已加载"));
            onEdt(() -> { assertFalse(history(reopened).contains("历史公共")); selectPeer(reopened,"bob"); assertTrue(history(reopened).contains("离线联系人历史")); });
            try (Peer bob=Peer.login(server.port(),"bob")) {
                bob.send(Message.of(MessageType.HISTORY_REQUEST).requestId("other-copy").build());
                assertEquals("历史公共一",bob.type(MessageType.HISTORY_PAGE).record().content());
                bob.send(Message.of(MessageType.PROFILE_UPDATE).requestId("rename").nickname("实时昵称").avatar("").build());
                assertEquals("实时昵称", bob.type(MessageType.PROFILE_OK).nickname());
                awaitEdt(() -> history(reopened).contains("[实时昵称] 离线联系人历史") && summary(reopened, "bob").profile().avatar() == null);
                onEdt(() -> {
                    assertEquals("实时昵称", summary(reopened, "bob").title());
                    assertEquals("实时昵称", label(reopened, "conversationTitle").getText());
                    assertNotEquals(avatarColor.getRGB(), iconCenter(label(reopened, "conversationAvatar")));
                });
                bob.privateTo("alice", "实时私聊");
                awaitEdt(() -> history(reopened).contains("[实时昵称] 实时私聊"));
                onEdt(() -> button(reopened, "group").doClick());
                bob.send(Message.of(MessageType.GROUP_SEND).requestId("forged-name").from("alice")
                        .nickname("伪造昵称").content("实时群聊").build());
                Message echo = bob.type(MessageType.GROUP_MESSAGE);
                assertEquals("bob", echo.from()); assertEquals("实时昵称", echo.nickname());
                awaitEdt(() -> history(reopened).contains("[实时昵称] 实时群聊"));
                onEdt(() -> assertEquals("实时昵称：实时群聊", summary(reopened, ConversationId.PUBLIC).preview()));
            }
        } finally { if(ref.get()!=null) onEdt(() -> ref.get().dispatchEvent(new WindowEvent(ref.get(),WindowEvent.WINDOW_CLOSING))); }
    }
    private static void confirmNext(ChatWindow window) {
        javax.swing.Timer timer=new javax.swing.Timer(50,null);
        timer.addActionListener(e -> {
            for(Window child:window.getOwnedWindows()) if(child instanceof JDialog dialog && dialog.isVisible()) {
                JOptionPane pane=findOptionPane(dialog);
                if(pane!=null) { timer.stop(); pane.setValue(JOptionPane.YES_OPTION); return; }
            }
        }); timer.start();
    }
    private static JOptionPane findOptionPane(Container root) {
        for(Component child:root.getComponents()) {
            if(child instanceof JOptionPane pane) return pane;
            if(child instanceof Container nested) { JOptionPane pane=findOptionPane(nested); if(pane!=null) return pane; }
        } return null;
    }
    private static JMenuItem messageDelete(Container root,String content) {
        for(Component child:root.getComponents()) {
            if(child instanceof JTextPane text && text.getText().equals(content) && text.getComponentPopupMenu()!=null)
                return (JMenuItem)find(text.getComponentPopupMenu(),"deleteMessage");
            if(child instanceof Container nested) { JMenuItem item=messageDelete(nested,content); if(item!=null) return item; }
        } return null;
    }
    private static void invokeKey(JTextArea editor, int key, int modifiers) {
        Object binding = editor.getInputMap().get(KeyStroke.getKeyStroke(key, modifiers));
        editor.getActionMap().get(binding).actionPerformed(new java.awt.event.ActionEvent(editor, 0, binding.toString()));
    }
    private static String history(ChatWindow window) { return ((MessageTimeline) find(window, "timeline")).renderedText(); }
    private static ConversationStore.Summary summary(ChatWindow window, String peer) {
        return summary(window, ConversationId.privateWith(peer));
    }
    private static ConversationStore.Summary summary(ChatWindow window, ConversationId id) {
        for (int i = 0; i < list(window).getModel().getSize(); i++) {
            ConversationStore.Summary summary = list(window).getModel().getElementAt(i);
            if (summary.id().equals(id)) return summary;
        }
        throw new AssertionError("Missing conversation: " + id);
    }
    private static int iconCenter(JLabel label) {
        Icon icon = label.getIcon();
        BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { icon.paintIcon(label, graphics, 0, 0); } finally { graphics.dispose(); }
        return image.getRGB(icon.getIconWidth() / 2, icon.getIconHeight() / 2);
    }
    private static void selectPeer(ChatWindow window, String peer) {
        list(window).setSelectedValue(summary(window, peer), true);
    }

    private static void onEdt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
    private static void awaitEdt(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean[] done = {false};
        do { onEdt(() -> done[0] = condition.getAsBoolean()); if (!done[0]) TimeUnit.MILLISECONDS.sleep(10); }
        while (!done[0] && System.nanoTime() < deadline);
        assertTrue(done[0], "Swing condition did not become true");
    }
    private static Component find(Container parent, String name) {
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container nested) { Component found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static JButton button(ChatWindow window, String name) { return (JButton) find(window, name); }
    private static JTextField text(ChatWindow window, String name) { return (JTextField) find(window, name); }
    private static JTextArea area(ChatWindow window, String name) { return (JTextArea) find(window, name); }
    private static JLabel label(ChatWindow window, String name) { return (JLabel) find(window, name); }
    @SuppressWarnings("unchecked") private static JList<ConversationStore.Summary> list(ChatWindow window) { return (JList<ConversationStore.Summary>) find(window, "conversations"); }

    private static void renderComponentPreview(ChatWindow window) {
        render(window, 960, 680, "chat-window.png");
    }
    private static void render(ChatWindow window, int width, int height, String filename) {
        renderContainer(window.getContentPane(), width, height, filename);
    }
    private static void renderContainer(Container root, int width, int height, String filename) {
        try {
            root.setSize(width, height); layout(root);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose();
            Path directory = Path.of("target", "ui-preview"); Files.createDirectories(directory);
            ImageIO.write(image, "png", directory.resolve(filename).toFile());
        } catch (Exception ex) { throw new AssertionError("Unable to render Swing component preview", ex); }
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
