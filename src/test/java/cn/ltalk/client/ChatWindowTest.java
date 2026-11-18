package cn.ltalk.client;

import cn.ltalk.common.MessageType;
import cn.ltalk.common.Attachment;
import cn.ltalk.common.Message;
import cn.ltalk.common.MessageCodec;
import cn.ltalk.common.ConversationScope;
import cn.ltalk.common.HistoryQuery;
import cn.ltalk.common.SocialAction;
import cn.ltalk.common.SocialItem;
import cn.ltalk.client.ui.*;
import cn.ltalk.server.ChatServer;
import cn.ltalk.server.SocialFixtures;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseEvent;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises our own non-visible Swing component tree on the EDT, without OS input automation. */
class ChatWindowTest {
    @TempDir Path appearanceDirectory;

    @Test @Timeout(25)
    void imageAndFileUploadsRequireExplicitConfirmation() throws Exception {
        Path imageFile = appearanceDirectory.resolve("测试图片.png");
        assertTrue(ImageIO.write(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "png", imageFile.toFile()));
        Path file = Files.writeString(appearanceDirectory.resolve("测试文档.txt"), "测试文件");
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            server.accounts().register("alice", "Alice", "test123!", false);
            try (Peer observer = Peer.login(server.port(), "bob")) {
                onEdt(() -> ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice",
                        appearanceDirectory.resolve("upload-confirm.properties"), List::of, (owner, kind, done, failed) -> {
                            Path selected = switch (kind) {
                                case CHAT_IMAGE -> imageFile;
                                case FILE -> file;
                                default -> throw new AssertionError(kind);
                            };
                            SwingUtilities.invokeLater(() -> done.accept(selected));
                        })));
                ChatWindow window = ref.get();
                login(window);
                awaitEdt(() -> button(window, "send").isEnabled());
                for (Attachment.Kind kind : Attachment.Kind.values()) {
                    String buttonName = kind == Attachment.Kind.IMAGE ? "sendImage" : "attach";
                    Path selected = kind == Attachment.Kind.IMAGE ? imageFile : file;
                    onEdt(() -> button(window, buttonName).doClick());
                    awaitEdt(() -> uploadConfirmation(window) != null);
                    onEdt(() -> {
                        JOptionPane pane = uploadConfirmation(window);
                        assertTrue(pane.getMessage().toString().contains("公共聊天室"));
                        assertTrue(pane.getMessage().toString().contains(selected.getFileName().toString()));
                        assertEquals(JOptionPane.PLAIN_MESSAGE, pane.getMessageType());
                        assertNull(pane.getIcon());
                        assertEquals("取消", pane.getInitialValue());
                    });
                    observer.assertNo(message -> message.attachment() != null, 100);
                    onEdt(() -> uploadConfirmation(window).setValue("取消"));
                    awaitEdt(() -> uploadConfirmation(window) == null);
                    observer.assertNo(message -> message.attachment() != null, 100);

                    onEdt(() -> button(window, buttonName).doClick());
                    awaitEdt(() -> uploadConfirmation(window) != null);
                    onEdt(() -> uploadConfirmation(window).setValue("发送"));
                    Message uploaded = observer.await(message -> message.type() == MessageType.GROUP_MESSAGE
                            && message.attachment() != null);
                    assertEquals(kind, uploaded.attachment().kind());
                    if (kind == Attachment.Kind.IMAGE) {
                        assertEquals("图片.png", uploaded.attachment().name());
                        awaitEdt(() -> find(window, "previewImage") != null);
                        onEdt(() -> {
                            assertFalse(history(window).contains(selected.getFileName().toString()));
                            assertNull(find(window, "messageText"));
                        });
                    } else {
                        assertEquals(selected.getFileName().toString(), uploaded.attachment().name());
                        awaitEdt(() -> find(window, "fileAttachmentCard") != null);
                        onEdt(() -> {
                            assertTrue(history(window).contains(selected.getFileName().toString()));
                            render(window, 1100, 760, "attachment-cards-light.png");
                            window.applyAppearance(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK), false);
                            window.getContentPane().setSize(420, 680);
                            layout(window.getContentPane());
                            openPublic(window);
                        });
                        awaitEdt(() -> button(window, "previewImage").getIcon() != null);
                        onEdt(() -> {
                            render(window, 420, 680, "attachment-cards-dark-narrow.png");
                            assertTrue(find(window, "chatPane").isVisible());
                        });
                    }
                    awaitEdt(() -> java.util.Arrays.stream(window.getOwnedWindows())
                            .noneMatch(child -> child.isVisible() && "attachmentTransfer".equals(child.getName())));
                }
            }
        } finally { closeWindow(ref); }
    }

    @Test @Timeout(25)
    void sidebarMenuBlankClicksAndRecallUseTheirVisibleEntryPoints() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            server.accounts().register("alice", "Alice", "test123!", false);
            try (Peer bob = Peer.login(server.port(), "bob")) {
                onEdt(() -> ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice",
                        appearanceDirectory.resolve("recall.properties"))));
                ChatWindow window = ref.get();
                login(window);
                awaitEdt(() -> sidebarIds(window).contains(ConversationId.privateWith("bob")));
                onEdt(() -> {
                    ChatPopupMenu menu = window.socialMenu();
                    assertEquals("通知", ((JMenuItem) menu.getComponent(0)).getText());
                    assertEquals("列表外的会话", ((JMenuItem) find(menu, "archivedConversations")).getText());
                    assertNull(find(menu, "newPublic"));
                    assertNull(find(menu, "socialFriends"));
                    JButton more = button(window, "createConversation");
                    assertEquals("更多操作", more.getAccessibleContext().getAccessibleName());
                    assertEquals(LineIcon.Kind.PLUS, ((LineIcon) more.getIcon()).kind());
                    render(window, 980, 740, "more-operations-sidebar.png");
                    JList<?> rows = list(window);
                    int blank = rows.getCellBounds(1, 1).y + rows.getCellBounds(1, 1).height + 15;
                    assertTrue(blank < rows.getHeight());
                    for (int event : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED))
                        rows.dispatchEvent(new MouseEvent(rows, event, System.currentTimeMillis(), 0, 20, blank, 1, false, MouseEvent.BUTTON1));
                    assertEquals("公共聊天室", label(window, "conversationTitle").getText());
                    button(window, "conversationIdentity").doClick();
                    assertNull(button(window, "infoClearHistory").getToolTipText());
                    button(window, "closeConversationInfo").doClick();
                    area(window, "input").setText("通过菜单撤回");
                    button(window, "send").doClick();
                });
                Message sent = bob.type(MessageType.GROUP_MESSAGE);
                awaitEdt(() -> messageAction(window, "通过菜单撤回", "recallMessage") != null);
                onEdt(() -> {
                    assertEquals("删除这条信息", messageDelete(window, "通过菜单撤回").getText());
                    messageAction(window, "通过菜单撤回", "recallMessage").doClick();
                });
                assertEquals(sent.messageId(), bob.type(MessageType.MESSAGE_RECALLED).messageId());
                awaitEdt(() -> history(window).contains("[消息已撤回]"));
                onEdt(() -> {
                    assertFalse(history(window).contains("通过菜单撤回"));
                    assertNull(messageAction(window, "[消息已撤回]", "recallMessage"));
                    assertTrue(summary(window, ConversationId.PUBLIC).preview().contains("[消息已撤回]"));
                });
            }
        } finally { closeWindow(ref); }
    }

    private static JOptionPane uploadConfirmation(ChatWindow window) {
        return visibleOptionPane(window, "确认发送");
    }

    private static JOptionPane visibleOptionPane(ChatWindow window, String title) {
        for (Window child : window.getOwnedWindows()) {
            if (child instanceof JDialog dialog && dialog.isVisible() && title.equals(dialog.getTitle()))
                return findOptionPane(dialog);
        }
        return null;
    }

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
                    finishLoading(socket, reader, ConversationScope.PUBLIC);
                    finishHistoryRequest(socket, reader, HistoryQuery.MESSAGES, "PRIVATE:alice:bob");
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
                    finishLoading(socket, reader, "PRIVATE:alice:bob");
                    finishHistoryRequest(socket, reader, HistoryQuery.MESSAGES, ConversationScope.PUBLIC);
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
                openPublic(window);
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
                openPublic(window);
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

    private static void finishLoading(Socket socket, MessageCodec.FrameReader reader, String selected) throws Exception {
        finishHistoryRequest(socket, reader, HistoryQuery.MESSAGES, selected);
        finishHistoryRequest(socket, reader, HistoryQuery.SUMMARIES, null);
        Message social = reader.read();
        assertEquals(MessageType.SOCIAL_REQUEST, social.type());
        assertEquals(SocialAction.STATE.name(), social.socialAction());
        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.SOCIAL_ROW).requestId(social.requestId())
                .socialItem(SocialItem.user(SocialItem.Kind.PRIVATE_ALLOWED, "bob", "bob")).build());
        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.SOCIAL_END).requestId(social.requestId()).build());
        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.USER_LIST).revision(1L).users(List.of("alice", "bob")).build());
    }

    private static void finishHistoryRequest(Socket socket, MessageCodec.FrameReader reader, HistoryQuery mode, String scope) throws Exception {
        Message history = reader.read();
        assertEquals(MessageType.HISTORY_VIEW_REQUEST, history.type());
        assertEquals(mode.name(), history.historyMode());
        assertEquals(scope, history.conversationId());
        MessageCodec.write(socket.getOutputStream(), Message.of(MessageType.HISTORY_VIEW_END).requestId(history.requestId()).build());
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
                });
                awaitEdt(() -> button(window, "send").isEnabled());
                onEdt(() -> {
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
                    openPublic(window); assertTrue(button(window, "send").isEnabled());
                    area(window, "input").setText("UI-G-001 中文🙂\n<html><b>纯文本</b>"); button(window, "send").doClick();
                });
                awaitEdt(() -> history(window).contains("UI-G-001"));
                onEdt(() -> {
                    String history = history(window);
                    assertEquals(1, history.split("UI-G-001", -1).length - 1);
                    assertTrue(history.contains("<html><b>纯文本</b>"));
                    assertFalse(history.contains("UI-P-001"), "Private history must not leak into the public conversation");
                    assertEquals(1, list(window).getModel().getSize(), "Offline non-friends leave the public list");
                    openArchived(window, ConversationId.privateWith("bob")); assertTrue(history(window).contains("UI-P-001"));
                    assertTrue(label(window, "recipient").getText().contains("私聊 bob"));
                    openPublic(window);
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
                cn.ltalk.server.SocialFixtures.permitPrivate(server, "alice", "carol");
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
                    JTextComponent editor = area(window, "input"); editor.setCaretPosition(editor.getDocument().getLength());
                    invokeKey(editor, java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK);
                    editor.replaceSelection("第二行"); assertEquals("第一行\n第二行", editor.getText());
                    openPublic(window); assertEquals("公共草稿", editor.getText());
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
                    text(window, "conversationSearch").setText(""); openPublic(window);
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
                    for (String name : List.of("attach", "sendImage", "emoji")) {
                        assertTrue(button(window, name).isVisible(), name);
                        assertTrue(button(window, name).isEnabled(), name);
                        assertTrue(button(window, name).getWidth() > 0, name);
                    }
                    JTextComponent narrowEditor = area(window, "input");
                    assertTrue(narrowEditor.getWidth() > 80);
                    narrowEditor.setText("窄屏消息");
                    narrowEditor.setCaretPosition(2);
                    EmojiPicker picker = window.emojiPicker();
                    Dimension pickerSize = picker.getPreferredSize();
                    renderContainer(picker, pickerSize.width, pickerSize.height, "ui-emoji-picker.png");
                    JButton smile = (JButton) find(picker, "emoji-😀");
                    smile.doClick();
                    narrowEditor.setCaretPosition(narrowEditor.getDocument().getLength());
                    smile.doClick();
                    assertEquals("窄屏😀消息😀", narrowEditor.getText());
                    render(window, 420, 760, "ui-emoji-composer.png");
                    try {
                        Rectangle textRow = narrowEditor.modelToView2D(0).getBounds();
                        Point rowCenter = SwingUtilities.convertPoint(narrowEditor, textRow.x,
                                (int) Math.round(textRow.getCenterY()), window);
                        JButton attachment = button(window, "attach");
                        Point toolCenter = SwingUtilities.convertPoint(attachment, 0, attachment.getHeight() / 2, window);
                        assertTrue(Math.abs(rowCenter.y - toolCenter.y) <= 2, "Single line should align with toolbar icons");
                    } catch (javax.swing.text.BadLocationException ex) {
                        throw new AssertionError(ex);
                    }
                    invokeKey(narrowEditor, java.awt.event.KeyEvent.VK_ENTER, 0);
                    button(window, "back").doClick(); layout(window.getContentPane());
                    assertTrue(find(window, "sidebar").isVisible()); assertFalse(find(window, "chatPane").isVisible());
                    render(window, 420, 760, "ui-narrow-list.png");
                    openPublic(window); layout(window.getContentPane()); assertTrue(find(window, "chatPane").isVisible());
                    assertTrue(history(window).contains("讨论项目"));
                    area(window, "input").setText("\n "); button(window, "send").doClick();
                    assertEquals("\n ", area(window, "input").getText()); assertTrue(label(window, "status").getText().contains("空白"));
                });
                assertEquals("窄屏😀消息😀", carol.await(m -> "窄屏😀消息😀".equals(m.content())).content());
                carol.group("😀");
                awaitEdt(() -> summary(window, ConversationId.PUBLIC).preview().equals("carol：😀"));
                onEdt(() -> {
                    area(window, "input").setText("正文😀");
                    render(window, 1200, 780, "ui-emoji-tail.png");
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
                    openPublic(window); render(window, 420, 760, "ui-dark-narrow.png");
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
                button(window, "conversationIdentity").doClick();
                assertFalse(button(window, "infoExport").isEnabled());
                button(window, "closeConversationInfo").doClick();
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
                button(window, "conversationIdentity").doClick();
                assertTrue(button(window, "infoExport").isEnabled());
                button(window, "closeConversationInfo").doClick();
                ((JMenuItem) find(window.identityMenu(), "accountProfile")).doClick();
                JDialog dialog = profileDialog(window); assertNotNull(dialog);
                renderContainer(dialog.getContentPane(), 480, 570, "profile-editor.png");
                ((JTextField) find(dialog, "profileNickname")).setText("修改后的昵称");
                ((JButton) find(dialog, "profileSave")).doClick();
            });
            awaitEdt(() -> "修改后的昵称".equals(label(window, "identityNickname").getText()) && profileDialog(window) == null);
            assertEquals("修改后的昵称", server.accounts().profile("newUI").nickname());
            onEdt(() -> {
                openPublic(window); area(window, "input").setText("上一账号的草稿");
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
                    openPublic(window); area(window, "input").setText("原账号的草稿");
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
            awaitEdt(() -> list(window).getModel().getSize() == 1 && history(window).contains("历史公共一")
                    && history(window).contains("[离线昵称] 历史公共二") && label(window,"status").getText().contains("已加载")
                    && ((JMenuItem) find(window.socialMenu(), "archivedConversations")).isEnabled());
            onEdt(() -> {
                assertEquals("离线昵称：历史公共二", summary(window, ConversationId.PUBLIC).preview());
                var offline = openArchived(window, ConversationId.privateWith("bob"));
                assertEquals("离线昵称", offline.title());
                BufferedImage receivedAvatar = offline.profile().avatar();
                assertEquals(256, receivedAvatar.getWidth());
                assertEquals(avatarColor.getRGB(), receivedAvatar.getRGB(128, 128));
                assertFalse(history(window).contains("离线联系人历史"), "A summary must not add a message to the timeline");
                assertFalse(button(window,"send").isEnabled());
                assertEquals("离线昵称", label(window, "conversationTitle").getText());
                assertEquals(avatarColor.getRGB(), iconCenter(label(window, "conversationAvatar")));
            });
            awaitEdt(() -> history(window).contains("[离线昵称] 离线联系人历史"));
            onEdt(() -> {
                openPublic(window); area(window,"input").setText("删除不影响草稿");
                render(window,1000,760,"history-restored.png");
                JMenuItem item=messageDelete(window,"历史公共一"); assertNotNull(item); assertTrue(item.isEnabled());
                confirmNext(window); item.doClick();
            });
            awaitEdt(() -> !history(window).contains("历史公共一") && history(window).contains("历史公共二") && label(window,"status").getText().contains("已加载"));
            onEdt(() -> {
                assertEquals("删除不影响草稿",area(window,"input").getText()); confirmNext(window);
                button(window, "conversationIdentity").doClick();
                button(window, "infoClearHistory").doClick();
                button(window, "closeConversationInfo").doClick();
            });
            awaitEdt(() -> !history(window).contains("历史公共二") && label(window,"status").getText().contains("已加载"));
            onEdt(() -> window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING))); ref.set(null); awaitEdt(() -> server.onlineCount()==0);
            onEdt(() -> ref.set(new ChatWindow("127.0.0.1",Integer.toString(server.port()),"alice",appearanceDirectory.resolve("history.properties"))));
            ChatWindow reopened=ref.get(); login(reopened);
            awaitEdt(() -> list(reopened).getModel().getSize() == 1 && label(reopened,"status").getText().contains("已加载")
                    && ((JMenuItem) find(reopened.socialMenu(), "archivedConversations")).isEnabled());
            onEdt(() -> { assertFalse(history(reopened).contains("历史公共")); openArchived(reopened, ConversationId.privateWith("bob")); });
            awaitEdt(() -> history(reopened).contains("离线联系人历史"));
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
                onEdt(() -> openPublic(reopened));
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

    @Test @Timeout(25)
    void olderHistoryKeepsTheViewportAndSearchLocatesAnUnloadedMessageWithContext() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            try (Peer bob = Peer.login(server.port(), "bob")) {
                for (int i = 1; i <= 61; i++) {
                    bob.group(String.format("公开历史-%02d", i) + (i == 4 ? " 独特关键词" : ""));
                    bob.type(MessageType.GROUP_MESSAGE);
                }
                server.accounts().register("alice", "alice", "test123!", false);
                onEdt(() -> ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice",
                        appearanceDirectory.resolve("paged-history.properties"))));
                ChatWindow window = ref.get();
                login(window);
                awaitEdt(() -> history(window).contains("公开历史-42") && history(window).contains("公开历史-61"));
                int[] anchorY = new int[1];
                onEdt(() -> {
                    assertFalse(history(window).contains("公开历史-41"));
                    window.applyAppearance(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK), false);
                    openPublic(window);
                    render(window, 420, 680, "history-recent-narrow.png");
                    assertTrue(find(window, "chatPane").isVisible());
                });
                onEdt(() -> {
                    MessageTimeline timeline = (MessageTimeline) find(window, "timeline");
                    assertFalse(button(window, "returnLatest").isVisible());
                    timeline.getVerticalScrollBar().setValue(0);
                    JButton latest = button(window, "returnLatest");
                    assertTrue(latest.isVisible());
                    assertTrue(latest.getY() + latest.getHeight() < SwingUtilities.convertPoint(button(window, "send"), 0, 0, latest.getParent()).y);
                    anchorY[0] = viewportY(timeline, "公开历史-42");
                    assertTrue(anchorY[0] >= 0 && anchorY[0] < timeline.getViewport().getHeight());
                    timeline.dispatchEvent(new java.awt.event.MouseWheelEvent(timeline, java.awt.event.MouseEvent.MOUSE_WHEEL,
                            System.currentTimeMillis(), 0, 20, 40, 0, false,
                            java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1));
                });
                awaitEdt(() -> history(window).contains("公开历史-22"));
                onEdt(() -> {
                    MessageTimeline timeline = (MessageTimeline) find(window, "timeline");
                    assertEquals(anchorY[0], viewportY(timeline, "公开历史-42"));
                    assertFalse(history(window).contains("公开历史-21"));
                    assertFalse(history(window).contains("独特关键词"));
                    area(window, "input").setText("分页期间保留的草稿");
                    button(window, "returnLatest").doClick();
                });
                awaitEdt(() -> ((MessageTimeline) find(window, "timeline")).followingLatest() && !button(window, "returnLatest").isVisible());
                onEdt(() -> button(window, "chatSearch").doClick());
                JDialog dialog = historySearchDialog(window);
                onEdt(() -> {
                    ((JTextField) find(dialog, "messageSearch")).setText("独特关键词");
                    ((JButton) find(dialog, "submitHistorySearch")).doClick();
                });
                awaitEdt(() -> ((JList<?>) find(dialog, "historySearchResults")).getModel().getSize() == 1);
                onEdt(() -> {
                    renderContainer(dialog.getContentPane(), 420, 380, "history-search-narrow.png");
                    ((JButton) find(dialog, "locateHistoryResult")).doClick();
                });
                awaitEdt(() -> find(window, "locatedMessage") != null && !dialog.isDisplayable());
                onEdt(() -> {
                    assertTrue(history(window).contains("公开历史-03"));
                    assertTrue(history(window).contains("公开历史-05"));
                    assertFalse(history(window).contains("公开历史-61"));
                    MessageTimeline timeline = (MessageTimeline) find(window, "timeline");
                    int y = viewportY(timeline, "公开历史-04 独特关键词");
                    assertTrue(y >= 0 && y < timeline.getViewport().getHeight(), "Located message is in the viewport");
                    render(window, 420, 680, "history-location-narrow.png");
                });
                bob.group("定位期间收到的消息");
                bob.type(MessageType.GROUP_MESSAGE);
                awaitEdt(() -> summary(window, ConversationId.PUBLIC).preview().contains("定位期间收到的消息"));
                onEdt(() -> {
                    assertFalse(history(window).contains("定位期间收到的消息"));
                    button(window, "returnLatest").doClick();
                    assertTrue(history(window).contains("定位期间收到的消息"));
                    assertFalse(history(window).contains("独特关键词"));
                    assertEquals("分页期间保留的草稿", area(window, "input").getText());
                });
            }
        } finally { closeWindow(ref); }
    }

    private static JDialog historySearchDialog(ChatWindow window) throws Exception {
        return ownedDialog(window, "historySearchDialog");
    }

    private static JDialog ownedDialog(ChatWindow window, String name) throws Exception {
        AtomicReference<JDialog> dialog = new AtomicReference<>();
        onEdt(() -> {
            for (Window child : window.getOwnedWindows()) {
                if (name.equals(child.getName()) && child.isDisplayable()) dialog.set((JDialog) child);
            }
        });
        assertNotNull(dialog.get());
        return dialog.get();
    }

    @Test @Timeout(35)
    void chatInformationCardsAndSidebarFollowMembershipPresenceAndFriendship() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        Clipboard clipboard = new Clipboard("info-card-test");
        AtomicBoolean clipboardBusy = new AtomicBoolean();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            server.accounts().register("alice", "Alice", "test123!", false);
            server.accounts().register("bob", "小波", "test123!", false);
            server.accounts().register("carol", "离线成员", "test123!", false);
            try (Peer bob = Peer.login(server.port(), "bob")) {
                social(bob, Message.of(MessageType.SOCIAL_REQUEST).socialAction("GROUP_CREATE").nickname("项目小组").content("OPEN"));
                bob.send(Message.of(MessageType.SOCIAL_REQUEST).requestId("find-group").socialAction("FIND_GROUP").content("项目").build());
                SocialItem group = bob.await(frame -> "find-group".equals(frame.requestId()) && frame.type() == MessageType.SOCIAL_ROW).socialItem();
                String scope = ConversationScope.group(group.id());
                for (String user : List.of("alice", "carol")) {
                    try (Peer member = Peer.login(server.port(), user)) {
                        social(member, Message.of(MessageType.SOCIAL_REQUEST).socialAction("GROUP_REQUEST").conversationId(scope));
                    }
                    bob.users(1);
                }
                onEdt(() -> ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice",
                        appearanceDirectory.resolve("info.properties"), List::of, new FileDialogs()::choose, () -> {
                            if (clipboardBusy.get()) throw new IllegalStateException("Clipboard busy");
                            return clipboard;
                        })));
                ChatWindow window = ref.get();
                login(window);
                awaitEdt(() -> sidebarIds(window).equals(List.of(ConversationId.PUBLIC, ConversationId.privateWith("bob"))));
                onEdt(() -> {
                    button(window, "conversationIdentity").doClick();
                    assertEquals("公共聊天室无固定群号", label(window, "infoIdentifier").getText());
                    assertEquals("2 位成员 · 2 人在线", label(window, "infoDetail").getText());
                    assertNotNull(find(window, "groupMember-alice"));
                    assertNull(find(window, "groupLeave"));
                    assertFalse(button(window, "chatMore").isVisible());
                    ((JToggleButton) find(window, "contactsTab")).doClick();
                    assertEquals("公共聊天室", label(window, "conversationTitle").getText(), "Changing categories leaves the current chat open");
                });
                ConversationId groupId = ConversationId.group(group.id());
                awaitEdt(() -> sidebarIds(window).equals(List.of(groupId)));
                onEdt(() -> {
                    list(window).setSelectedIndex(0);
                    assertFalse(find(window, "conversationInfo").isVisible());
                    button(window, "conversationIdentity").doClick();
                });
                awaitEdt(() -> "3 位成员 · 2 人在线".equals(label(window, "infoDetail").getText()));
                onEdt(() -> {
                    JButton identifier = button(window, "infoIdentifier");
                    assertEquals("群号 " + group.id(), identifier.getText());
                    identifier.doClick();
                    assertEquals(group.id(), assertDoesNotThrow(() -> clipboard.getData(DataFlavor.stringFlavor)));
                    assertEquals("已复制到剪贴板", label(window, "infoFeedback").getText());
                    assertTrue(button(window, "infoExport").isEnabled());
                    assertTrue(button(window, "infoClearHistory").isEnabled());
                    assertEquals("删除所有聊天记录", button(window, "infoClearHistory").getText());
                    assertEquals("重新加载聊天记录", button(window, "infoReloadHistory").getText());
                    assertTrue(button(window, "groupLeave").isEnabled());
                    assertNull(find(window, "groupDisband"));
                    render(window, 1280, 760, "group-information.png");
                    assertTrue(find(window, "chatContent").isVisible());
                    assertTrue(find(window, "conversationInfo").getX() > find(window, "chatContent").getWidth());
                    button(window, "groupMember-carol").doClick();
                    assertEquals("离线成员", label(window, "infoName").getText());
                    assertFalse(label(window, "infoFeedback").isVisible());
                    JButton username = button(window, "infoIdentifier");
                    assertEquals("@carol", username.getText());
                    username.doClick();
                    assertEquals("carol", assertDoesNotThrow(() -> clipboard.getData(DataFlavor.stringFlavor)));
                    assertEquals("离线", label(window, "infoDetail").getText());
                    assertTrue(button(window, "infoRequestFriend").isEnabled());
                    button(window, "backToGroupInfo").doClick();
                    button(window, "closeConversationInfo").doClick();
                    area(window, "input").setText("仍保留的群聊草稿");
                });
                try (Peer carol = Peer.login(server.port(), "carol")) {
                    bob.send(Message.of(MessageType.GROUP_SEND).requestId("card-message").conversationId(scope).content("头像打开资料").build());
                    awaitEdt(() -> history(window).contains("头像打开资料"));
                    onEdt(() -> {
                        render(window, 1280, 760, "group-before-avatar.png");
                        clickMessageAvatar(window, "头像打开资料");
                        JButton username = button(window, "infoIdentifier");
                        assertEquals("@bob", username.getText());
                        clipboardBusy.set(true);
                        username.doClick();
                        assertEquals("无法写入剪贴板，请稍后重试", label(window, "infoFeedback").getText());
                        assertEquals("carol", assertDoesNotThrow(() -> clipboard.getData(DataFlavor.stringFlavor)));
                        clipboardBusy.set(false);
                        username.doClick();
                        assertEquals("bob", assertDoesNotThrow(() -> clipboard.getData(DataFlavor.stringFlavor)));
                        assertEquals("小波", label(window, "infoName").getText());
                        button(window, "infoSendMessage").doClick();
                        assertEquals("小波", label(window, "conversationTitle").getText());
                        assertEquals("删除所有聊天记录", ((JMenuItem) find(window.conversationMenu(), "chatClearHistory")).getText());
                        assertFalse(button(window, "send").isEnabled());
                        assertTrue(button(window, "requestPrivate").isVisible());
                        button(window, "conversationIdentity").doClick();
                        window.applyAppearance(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK), false);
                        render(window, 420, 680, "user-information-narrow.png");
                        assertTrue(ChatTheme.palette(button(window, "infoRequestFriend")).dark());
                        assertFalse(find(window, "chatContent").isVisible());
                        button(window, "infoRequestFriend").doClick();
                    });
                    awaitEdt(() -> "好友申请待处理".equals(button(window, "infoRequestFriend").getText()));
                    bob.send(Message.of(MessageType.SOCIAL_REQUEST).requestId("friend-in").socialAction("STATE").build());
                    Message request = bob.await(frame -> "friend-in".equals(frame.requestId()) && frame.type() == MessageType.SOCIAL_ROW
                            && frame.socialItem().kind() == SocialItem.Kind.FRIEND_IN);
                    social(bob, Message.of(MessageType.SOCIAL_REQUEST).socialAction("FRIEND_ACCEPT").messageId(request.socialItem().id()));
                    awaitEdt(() -> find(window, "infoRemoveFriend") != null && button(window, "send").isEnabled());
                    onEdt(() -> {
                        assertEquals("在线 · 好友", label(window, "infoDetail").getText());
                        assertTrue(sidebarIds(window).contains(ConversationId.privateWith("bob")));
                        button(window, "closeConversationInfo").doClick();
                        assertTrue(find(window, "chatContent").isVisible());
                        render(window, 1280, 760, "group-friends-tab.png");
                        list(window).setSelectedValue(summary(window, groupId), true);
                        assertEquals("仍保留的群聊草稿", area(window, "input").getText());
                        button(window, "conversationIdentity").doClick();
                    });
                    awaitEdt(() -> "3 位成员 · 3 人在线".equals(label(window, "infoDetail").getText()));
                    social(carol, Message.of(MessageType.SOCIAL_REQUEST).socialAction("GROUP_LEAVE").conversationId(scope));
                    awaitEdt(() -> "2 位成员 · 2 人在线".equals(label(window, "infoDetail").getText()) && find(window, "groupMember-carol") == null);
                    bob.close();
                    awaitEdt(() -> "2 位成员 · 1 人在线".equals(label(window, "infoDetail").getText()));
                    onEdt(() -> {
                        assertTrue(sidebarIds(window).contains(ConversationId.privateWith("bob")), "Offline friends remain in this tab");
                        assertFalse(sidebarIds(window).contains(ConversationId.privateWith("carol")), "Online non-friends are excluded from this tab");
                        button(window, "groupMember-bob").doClick();
                        assertEquals("离线 · 好友", label(window, "infoDetail").getText());
                        confirmNext(window);
                        button(window, "infoRemoveFriend").doClick();
                    });
                    awaitEdt(() -> sidebarIds(window).equals(List.of(groupId)) && find(window, "infoRequestFriend") != null);
                    onEdt(() -> {
                        button(window, "backToGroupInfo").doClick();
                        confirmNext(window);
                        button(window, "groupLeave").doClick();
                    });
                    awaitEdt(() -> sidebarIds(window).isEmpty() && "你已退出群聊".equals(label(window, "infoDetail").getText()));
                    onEdt(() -> {
                        assertTrue(history(window).contains("头像打开资料"));
                        assertNull(find(window, "groupMember-bob"));
                        assertFalse(button(window, "send").isEnabled());
                        ((JToggleButton) find(window, "group")).doClick();
                        assertEquals(List.of(ConversationId.PUBLIC, ConversationId.privateWith("carol")), sidebarIds(window));
                    });
                }
            }
        } finally { closeWindow(ref); }
    }

    private static void social(Peer peer, Message.Builder request) throws Exception {
        String id = UUID.randomUUID().toString();
        peer.send(request.requestId(id).build());
        assertEquals(MessageType.SOCIAL_END, peer.await(frame -> id.equals(frame.requestId())).type());
    }

    private static List<ConversationId> sidebarIds(ChatWindow window) {
        var rows = list(window).getModel();
        return java.util.stream.IntStream.range(0, rows.getSize()).mapToObj(rows::getElementAt).map(ConversationStore.Summary::id).toList();
    }

    @Test @Timeout(40)
    void administratorCanBrowseAllRosterPagesSearchAndTransferToAnOfflineMember() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            String groupId;
            try (Peer admin = Peer.login(server.port(), "alice")) {
                social(admin, Message.of(MessageType.SOCIAL_REQUEST).socialAction("GROUP_CREATE").nickname("转让测试群").content("OPEN"));
                admin.send(Message.of(MessageType.SOCIAL_REQUEST).requestId("find-transfer-group").socialAction("FIND_GROUP")
                        .content("转让测试群").build());
                groupId = admin.await(frame -> "find-transfer-group".equals(frame.requestId()) && frame.type() == MessageType.SOCIAL_ROW)
                        .socialItem().id();
                String scope = ConversationScope.group(groupId);
                for (int i = 0; i < SocialAction.PAGE_SIZE; i++) {
                    String user = "member_%02d".formatted(i);
                    server.accounts().register(user, "成员 " + i, "test123!", false);
                    SocialFixtures.joinOpenGroup(server, user, scope);
                }
                social(admin, Message.of(MessageType.SOCIAL_REQUEST).socialAction("GROUP_REVIEW").conversationId(scope).content("REVIEW"));
            }
            try (Peer leaving = Peer.login(server.port(), "member_00")) {
                onEdt(() -> ref.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice",
                        appearanceDirectory.resolve("transfer-admin.properties"))));
                ChatWindow window = ref.get();
                ConversationId group = ConversationId.group(groupId);
                login(window);
                awaitEdt(() -> button(window, "send").isEnabled());
                onEdt(() -> ((JToggleButton) find(window, "contactsTab")).doClick());
                awaitEdt(() -> sidebarIds(window).contains(group));
                onEdt(() -> {
                    list(window).setSelectedValue(summary(window, group), true);
                    button(window, "conversationIdentity").doClick();
                    assertEquals("取消入群审核", button(window, "groupReview").getText());
                });

                JDialog first = openGroupTransfer(window);
                JList<ConversationInfoPanel.Member> firstRows = transferMembers(first);
                JButton firstConfirm = (JButton) find(first, "groupTransferConfirm");
                awaitEdt(() -> firstRows.getModel().getSize() == SocialAction.PAGE_SIZE + 1);
                onEdt(() -> {
                    assertFalse(firstConfirm.isEnabled());
                    JTextField query = (JTextField) find(first, "groupTransferSearch");
                    query.setText("BER_23");
                    assertEquals(1, firstRows.getModel().getSize());
                    assertEquals("member_23", firstRows.getModel().getElementAt(0).user());
                    query.setText("");
                    for (int i = 0; i < firstRows.getModel().getSize(); i++) {
                        if ("member_00".equals(firstRows.getModel().getElementAt(i).user())) firstRows.setSelectedIndex(i);
                    }
                    assertTrue(firstConfirm.isEnabled());
                });
                social(leaving, Message.of(MessageType.SOCIAL_REQUEST).socialAction("GROUP_LEAVE")
                        .conversationId(ConversationScope.group(groupId)));
                awaitEdt(() -> firstRows.getModel().getSize() == SocialAction.PAGE_SIZE && !firstConfirm.isEnabled());
                onEdt(() -> {
                    for (int i = 0; i < firstRows.getModel().getSize(); i++) {
                        if ("member_01".equals(firstRows.getModel().getElementAt(i).user())) firstRows.setSelectedIndex(i);
                    }
                    assertTrue(firstConfirm.isEnabled());
                    ((JButton) find(first, "groupTransferCancel")).doClick();
                });
                awaitEdt(() -> !first.isDisplayable());

                JDialog second = openGroupTransfer(window);
                JList<ConversationInfoPanel.Member> secondRows = transferMembers(second);
                awaitEdt(() -> secondRows.getModel().getSize() == SocialAction.PAGE_SIZE);
                onEdt(() -> {
                    JTextField query = (JTextField) find(second, "groupTransferSearch");
                    JButton confirm = (JButton) find(second, "groupTransferConfirm");
                    query.setText("@ALIC");
                    assertEquals(1, secondRows.getModel().getSize());
                    secondRows.setSelectedIndex(0);
                    assertFalse(confirm.isEnabled());
                    query.setText("unmatched");
                    assertEquals(0, secondRows.getModel().getSize());
                    assertFalse(confirm.isEnabled());
                    query.setText("BER_23");
                    assertEquals(1, secondRows.getModel().getSize());
                    assertEquals("member_23", secondRows.getModel().getElementAt(0).user());
                    renderContainer(second.getContentPane(), 360, 370, "group-transfer-search.png");
                    secondRows.setSelectedIndex(0);
                    assertTrue(confirm.isEnabled());
                    confirm.doClick();
                });
                awaitEdt(() -> find(window, "groupTransfer") == null && find(window, "groupLeave") != null);
                onEdt(() -> assertEquals("管理员已转让", label(window, "infoFeedback").getText()));
                leaving.send(Message.of(MessageType.SOCIAL_REQUEST).requestId("transferred").socialAction("FIND_GROUP")
                        .content(groupId).build());
                assertEquals("member_23", leaving.await(frame -> "transferred".equals(frame.requestId())
                        && frame.type() == MessageType.SOCIAL_ROW).socialItem().admin());
            }
        } finally { closeWindow(ref); }
    }

    private static JDialog openGroupTransfer(ChatWindow window) throws Exception {
        SwingUtilities.invokeLater(() -> button(window, "groupTransfer").doClick());
        awaitEdt(() -> java.util.Arrays.stream(window.getOwnedWindows())
                .anyMatch(dialog -> "groupTransferDialog".equals(dialog.getName()) && dialog.isShowing()));
        return ownedDialog(window, "groupTransferDialog");
    }

    @SuppressWarnings("unchecked")
    private static JList<ConversationInfoPanel.Member> transferMembers(JDialog dialog) {
        return (JList<ConversationInfoPanel.Member>) find(dialog, "groupTransferMembers");
    }

    private static void clickMessageAvatar(ChatWindow window, String content) {
        MessageTimeline timeline = (MessageTimeline) find(window, "timeline");
        Container column = (Container) timeline.getViewport().getView();
        for (Component row : column.getComponents()) {
            if (!(row instanceof Container candidate) || messageDelete(candidate, content) == null) continue;
            int x = 28, y = Math.min(row.getY() + row.getHeight(), timeline.getViewport().getViewRect().y + timeline.getViewport().getHeight()) - 22;
            Component target = SwingUtilities.getDeepestComponentAt(column, x, y);
            Point point = SwingUtilities.convertPoint(column, x, y, target);
            target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                    point.x, point.y, 1, false, MouseEvent.BUTTON1));
            return;
        }
        fail("Could not find an avatar for " + content);
    }

    @Test @Timeout(35)
    void socialMenusFindAnOfflineFriendApproveTheRequestAndOpenAnIndependentGroup() throws Exception {
        AtomicReference<ChatWindow> alice = new AtomicReference<>(), bob = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            server.accounts().register("alice", "Alice", "test123!", false);
            server.accounts().register("bob", "北辰小队", "test123!", false);
            String port = Integer.toString(server.port());
            onEdt(() -> alice.set(new ChatWindow("127.0.0.1", port, "alice", appearanceDirectory.resolve("social-alice.properties"))));
            ChatWindow a = alice.get();
            login(a);
            awaitEdt(() -> button(a, "send").isEnabled());
            onEdt(() -> ((JMenuItem) find(a.socialMenu(), "socialFindUser")).doClick());
            JDialog lookup = ownedDialog(a, "socialDialog");
            onEdt(() -> {
                ((JTextField) find(lookup, "socialQuery")).setText("辰小");
                ((JButton) find(lookup, "socialSearch")).doClick();
            });
            awaitEdt(() -> ((JList<?>) find(lookup, "socialRows")).getModel().getSize() == 1);
            onEdt(() -> ((JButton) find(lookup, "socialPrimary")).doClick());
            awaitEdt(() -> ((JButton) find(lookup, "socialSecondary")).isEnabled());
            onEdt(() -> {
                ((JButton) find(lookup, "socialSecondary")).doClick();
                assertFalse(button(a, "send").isEnabled());
                assertFalse(button(a, "requestPrivate").isVisible());
                bob.set(new ChatWindow("127.0.0.1", port, "bob", appearanceDirectory.resolve("social-bob.properties")));
            });
            ChatWindow b = bob.get();
            login(b);
            awaitEdt(() -> button(b, "requestInbox").isVisible());
            onEdt(() -> {
                b.applyAppearance(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK), false);
                button(b, "requestInbox").doClick();
            });
            JDialog requests = ownedDialog(b, "socialDialog");
            onEdt(() -> {
                JList<?> rows = (JList<?>) find(requests, "socialRows");
                assertEquals(SocialItem.Kind.FRIEND_IN, ((SocialItem) rows.getModel().getElementAt(0)).kind());
                rows.setSelectedIndex(0);
                renderContainer(requests.getContentPane(), 420, 430, "social-requests-narrow.png");
                ((JButton) find(requests, "socialPrimary")).doClick();
            });
            awaitEdt(() -> button(a, "send").isEnabled() && label(a, "recipient").getText().contains("好友在线"));
            closeWindow(bob);
            bob.set(null);
            awaitEdt(() -> label(a, "recipient").getText().contains("好友离线"));
            onEdt(() -> {
                assertTrue(button(a, "send").isEnabled());
                area(a, "input").setText("给离线好友留言");
                button(a, "send").doClick();
            });
            awaitEdt(() -> hasSendStatus(a, "已确认") && history(a).contains("给离线好友留言"));
            for (int choice : List.of(JOptionPane.CANCEL_OPTION, JOptionPane.OK_OPTION)) {
                SwingUtilities.invokeLater(() -> ((JMenuItem) find(a.socialMenu(), "socialCreateGroup")).doClick());
                awaitEdt(() -> visibleOptionPane(a, "创建群组") != null);
                onEdt(() -> {
                    JOptionPane pane = visibleOptionPane(a, "创建群组");
                    assertEquals(JOptionPane.PLAIN_MESSAGE, pane.getMessageType());
                    assertNull(pane.getIcon());
                    JTextField name = (JTextField) find(pane, "groupName");
                    name.setText(choice == JOptionPane.OK_OPTION ? "UI 设计群" : "取消创建的群");
                    pane.setValue(choice);
                });
                awaitEdt(() -> visibleOptionPane(a, "创建群组") == null);
            }
            onEdt(() -> ((JToggleButton) find(a, "contactsTab")).doClick());
            awaitEdt(() -> list(a).getModel().getSize() == 2);
            onEdt(() -> {
                for (int i = 0; i < list(a).getModel().getSize(); i++) {
                    var row = list(a).getModel().getElementAt(i);
                    if ("UI 设计群".equals(row.title())) list(a).setSelectedIndex(i);
                }
                assertTrue(label(a, "recipient").getText().contains("管理员"));
                button(a, "conversationIdentity").doClick();
                assertTrue(button(a, "groupTransfer").isEnabled());
                button(a, "closeConversationInfo").doClick();
                assertTrue(button(a, "send").isEnabled());
                area(a, "input").setText("群组专属消息");
                button(a, "send").doClick();
            });
            awaitEdt(() -> history(a).contains("群组专属消息") && hasSendStatus(a, "已确认"));
            onEdt(() -> {
                render(a, 420, 680, "social-group-narrow.png");
                openPublic(a);
                assertFalse(history(a).contains("群组专属消息"));
            });
        } finally {
            closeWindow(bob);
            closeWindow(alice);
        }
    }

    @Test @Timeout(35)
    void openingNarrowConversationJumpsToFirstUnreadAndQuotedReplyCanLocateUnloadedOriginal() throws Exception {
        AtomicReference<ChatWindow> ref = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            Peer.seed(server);
            try (Peer bob = Peer.login(server.port(), "bob")) {
                String port = Integer.toString(server.port());
                onEdt(() -> ref.set(new ChatWindow("127.0.0.1", port, "alice", appearanceDirectory.resolve("unread.properties"))));
                ChatWindow window = ref.get();
                login(window);
                awaitEdt(() -> button(window, "send").isEnabled() && list(window).getModel().getSize() == 2);
                String first = "未读消息 1";
                bob.privateTo("alice", first);
                String originalId = bob.type(MessageType.PRIVATE_MESSAGE).messageId();
                for (int i = 2; i <= 30; i++) {
                    bob.privateTo("alice", "未读消息 " + i);
                    bob.type(MessageType.PRIVATE_MESSAGE);
                }
                awaitEdt(() -> summary(window, "bob").unread() == 30);
                onEdt(() -> {
                    window.applyAppearance(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK), false);
                    window.getContentPane().setSize(420, 680);
                    layout(window.getContentPane());
                    selectPeer(window, "bob");
                    layout(window.getContentPane());
                });
                awaitEdt(() -> {
                    if (summary(window, "bob").unread() != 0 || find(window, "unreadDivider") == null) return false;
                    MessageTimeline timeline = (MessageTimeline) find(window, "timeline");
                    int y = viewportY(timeline, first);
                    return y >= 0 && y < timeline.getViewport().getHeight()
                            && viewportY(timeline, "未读消息 30") >= timeline.getViewport().getHeight();
                });
                bob.privateTo("alice", "阅读期间的新消息");
                bob.type(MessageType.PRIVATE_MESSAGE);
                awaitEdt(() -> button(window, "newMessages").isVisible() && summary(window, "bob").unread() == 1);
                onEdt(() -> {
                    button(window, "newMessages").doClick();
                    assertEquals(0, summary(window, "bob").unread());
                    area(window, "input").setText("已有草稿");
                    JMenuItem quote = messageAction(window, first, "replyMessage");
                    assertNotNull(quote);
                    assertTrue(quote.isEnabled());
                    quote.doClick();
                    assertTrue(label(window, "quoteDraftText").getText().contains(first));
                    assertEquals("已有草稿", area(window, "input").getText());
                    button(window, "cancelQuote").doClick();
                    assertFalse(label(window, "quoteDraftText").getParent().isVisible());
                    quote.doClick();
                    openPublic(window);
                    assertFalse(label(window, "quoteDraftText").getParent().isVisible());
                    selectPeer(window, "bob");
                    assertTrue(label(window, "quoteDraftText").getParent().isVisible());
                    assertEquals("已有草稿", area(window, "input").getText());
                    render(window, 420, 680, "quote-composer-narrow.png");
                    button(window, "send").doClick();
                });
                Message reply = bob.await(m -> m.type() == MessageType.PRIVATE_MESSAGE && "alice".equals(m.from()));
                assertEquals(originalId, reply.replyTo());
                assertEquals("已有草稿", reply.content());
                awaitEdt(() -> hasSendStatus(window, "已确认"));
                closeWindow(ref);
                ref.set(null);
                awaitEdt(() -> server.onlineCount() == 1);
                onEdt(() -> ref.set(new ChatWindow("127.0.0.1", port, "alice", appearanceDirectory.resolve("unread.properties"))));
                ChatWindow reopened = ref.get();
                login(reopened);
                awaitEdt(() -> list(reopened).getModel().getSize() == 2);
                onEdt(() -> selectPeer(reopened, "bob"));
                awaitEdt(() -> find(reopened, "quoteLink") != null);
                onEdt(() -> {
                    assertEquals("查看引用消息", button(reopened, "quoteLink").getText());
                    assertFalse(history(reopened).lines().anyMatch(line -> line.endsWith(first)));
                    button(reopened, "quoteLink").doClick();
                });
                awaitEdt(() -> find(reopened, "locatedMessage") != null);
                onEdt(() -> {
                    assertTrue(history(reopened).lines().anyMatch(line -> line.endsWith(first)));
                    assertTrue(history(reopened).contains("未读消息 2"));
                    assertFalse(history(reopened).contains("已有草稿"));
                });
            }
        } finally { closeWindow(ref); }
    }

    private static int viewportY(MessageTimeline timeline, String content) {
        Container column = (Container) timeline.getViewport().getView();
        for (Component row : column.getComponents()) {
            Component body = row instanceof Container container ? find(container, "messageText") : null;
            if (body instanceof JTextPane text && content.equals(text.getText()))
                return SwingUtilities.convertPoint(text, new Point(), timeline.getViewport()).y;
        }
        throw new AssertionError("Missing message: " + content);
    }

    private static JOptionPane findOptionPane(Container root) {
        for(Component child:root.getComponents()) {
            if(child instanceof JOptionPane pane) return pane;
            if(child instanceof Container nested) { JOptionPane pane=findOptionPane(nested); if(pane!=null) return pane; }
        } return null;
    }
    private static JMenuItem messageDelete(Container root,String content) {
        return messageAction(root, content, "deleteMessage");
    }
    private static JMenuItem messageAction(Container root, String content, String action) {
        for(Component child:root.getComponents()) {
            if(child instanceof JTextPane text && text.getText().equals(content) && text.getComponentPopupMenu()!=null)
                return (JMenuItem)find(text.getComponentPopupMenu(),action);
            if(child instanceof Container nested) { JMenuItem item=messageAction(nested,content,action); if(item!=null) return item; }
        } return null;
    }
    private static void invokeKey(JTextComponent editor, int key, int modifiers) {
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

    private static void openPublic(ChatWindow window) {
        ((JToggleButton) find(window, "group")).doClick();
        JList<ConversationStore.Summary> rows = list(window);
        ConversationStore.Summary room = summary(window, ConversationId.PUBLIC);
        if (room.equals(rows.getSelectedValue())) {
            rows.getActionMap().get("open-conversation").actionPerformed(new java.awt.event.ActionEvent(rows, 0, "open-conversation"));
        } else rows.setSelectedValue(room, true);
    }

    private static ConversationStore.Summary openArchived(ChatWindow window, ConversationId id) {
        AtomicReference<ConversationStore.Summary> chosen = new AtomicReference<>();
        javax.swing.Timer timer = new javax.swing.Timer(25, null);
        timer.addActionListener(event -> {
            for (Window child : window.getOwnedWindows()) {
                if (!child.isShowing() || !(find(child, "archivedConversations") instanceof JList<?> rows)) continue;
                for (int index = 0; index < rows.getModel().getSize(); index++) {
                    var row = (ConversationStore.Summary) rows.getModel().getElementAt(index);
                    if (!id.equals(row.id())) continue;
                    chosen.set(row);
                    rows.setSelectedIndex(index);
                    break;
                }
                findOptionPane(child).setValue(chosen.get() == null ? JOptionPane.CANCEL_OPTION : JOptionPane.OK_OPTION);
                timer.stop();
                return;
            }
        });
        JMenuItem item = (JMenuItem) find(window.socialMenu(), "archivedConversations");
        assertTrue(item.isEnabled());
        timer.start();
        try { item.doClick(); } finally { timer.stop(); }
        assertNotNull(chosen.get(), "Missing archived conversation " + id);
        return chosen.get();
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
    private static JTextComponent area(ChatWindow window, String name) { return (JTextComponent) find(window, name); }
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
