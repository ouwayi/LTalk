package cn.ltalk.client;

import cn.ltalk.common.MessageType;
import cn.ltalk.common.Message;
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
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises our own non-visible Swing component tree on the EDT, without OS input automation. */
class ChatWindowTest {
    @TempDir Path appearanceDirectory;
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
                assertEquals("未登录", label(window, "identityNickname").getText());
            });
            awaitEdt(() -> button(window, "connect").isEnabled() && server.onlineCount() == 0);
            onEdt(() -> { text(window, "userId").setText("other"); text(window, "password").setText("test123!"); button(window, "connect").doClick(); });
            awaitEdt(() -> button(window, "send").isEnabled());
            onEdt(() -> { assertEquals("@other", label(window, "identityUsername").getText()); assertEquals("", area(window, "input").getText()); });
        } finally { if (ref.get() != null) onEdt(() -> ref.get().dispatchEvent(new WindowEvent(ref.get(), WindowEvent.WINDOW_CLOSING))); }
    }
    private static JDialog profileDialog(ChatWindow window) {
        for (Window child : window.getOwnedWindows()) if (child instanceof JDialog dialog && "profileDialog".equals(dialog.getName()) && dialog.isDisplayable()) return dialog;
        return null;
    }

    private static void login(ChatWindow window) throws Exception {
        awaitEdt(() -> button(window, "connect").isEnabled());
        onEdt(() -> { text(window, "password").setText("test123!"); button(window, "connect").doClick(); });
    }
    private static void invokeKey(JTextArea editor, int key, int modifiers) {
        Object binding = editor.getInputMap().get(KeyStroke.getKeyStroke(key, modifiers));
        editor.getActionMap().get(binding).actionPerformed(new java.awt.event.ActionEvent(editor, 0, binding.toString()));
    }
    private static String history(ChatWindow window) { return ((MessageTimeline) find(window, "timeline")).renderedText(); }
    private static ConversationStore.Summary summary(ChatWindow window, String peer) {
        for (int i = 0; i < list(window).getModel().getSize(); i++) {
            ConversationStore.Summary summary = list(window).getModel().getElementAt(i);
            if (summary.id().equals(ConversationId.privateWith(peer))) return summary;
        }
        throw new AssertionError("Missing conversation: " + peer);
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
