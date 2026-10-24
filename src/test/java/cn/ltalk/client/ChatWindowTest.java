package cn.ltalk.client;

import cn.ltalk.common.MessageType;
import cn.ltalk.server.ChatServer;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
    @Test @Timeout(20)
    void connectionPrivateTargetLossDraftAndGroupEcho() throws Exception {
        AtomicReference<ChatWindow> windowRef = new AtomicReference<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start();
            try (Peer bob = Peer.login(server.port(), "bob")) {
                SwingUtilities.invokeAndWait(() -> windowRef.set(new ChatWindow("127.0.0.1", Integer.toString(server.port()), "alice")));
                ChatWindow window = windowRef.get();
                onEdt(() -> {
                    assertFalse(button(window, "send").isEnabled());
                    text(window, "port").setText("wrong"); button(window, "connect").doClick();
                    assertTrue(label(window, "status").getText().contains("端口"));
                    text(window, "port").setText(Integer.toString(server.port())); button(window, "connect").doClick();
                });
                awaitEdt(() -> button(window, "send").isEnabled() && list(window).getModel().getSize() == 2);
                onEdt(() -> {
                    assertFalse(text(window, "host").isEnabled());
                    list(window).setSelectedValue("bob", true);
                    assertTrue(label(window, "recipient").getText().contains("私聊 bob"));
                    area(window, "input").setText("UI-P-001"); button(window, "send").doClick();
                    assertEquals("", area(window, "input").getText());
                });
                assertEquals("UI-P-001", bob.type(MessageType.PRIVATE_MESSAGE).content());
                awaitEdt(() -> area(window, "transcript").getText().contains("UI-P-001"));
                onEdt(() -> area(window, "input").setText("保留的私聊草稿"));
                bob.close();
                awaitEdt(() -> list(window).getModel().getSize() == 1 && !button(window, "send").isEnabled());
                onEdt(() -> {
                    assertEquals("保留的私聊草稿", area(window, "input").getText());
                    assertTrue(label(window, "recipient").getText().contains("重新选择"));
                    button(window, "group").doClick(); assertTrue(button(window, "send").isEnabled());
                    area(window, "input").setText("UI-G-001 中文🙂\n<html><b>纯文本</b>"); button(window, "send").doClick();
                });
                awaitEdt(() -> area(window, "transcript").getText().contains("UI-G-001"));
                onEdt(() -> {
                    String history = area(window, "transcript").getText();
                    assertEquals(1, history.split("UI-G-001", -1).length - 1);
                    assertTrue(history.contains("<html><b>纯文本</b>"));
                    assertTrue(history.contains("[私聊 alice → bob]"));
                    renderComponentPreview(window);
                    button(window, "disconnect").doClick();
                });
                awaitEdt(() -> button(window, "connect").isEnabled());
                onEdt(() -> { assertFalse(button(window, "send").isEnabled()); assertTrue(text(window, "host").isEnabled()); });
            }
        } finally {
            if (windowRef.get() != null) onEdt(() -> windowRef.get().dispatchEvent(new WindowEvent(windowRef.get(), WindowEvent.WINDOW_CLOSING)));
        }
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
    @SuppressWarnings("unchecked") private static JList<String> list(ChatWindow window) { return (JList<String>) find(window, "online"); }

    private static void renderComponentPreview(ChatWindow window) {
        try {
            Container root = window.getContentPane(); root.setSize(960, 680); layout(root);
            BufferedImage image = new BufferedImage(960, 680, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose();
            Path directory = Path.of("target", "ui-preview"); Files.createDirectories(directory);
            ImageIO.write(image, "png", directory.resolve("chat-window.png").toFile());
        } catch (Exception ex) { throw new AssertionError("Unable to render Swing component preview", ex); }
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
