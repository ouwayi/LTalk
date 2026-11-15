package cn.ltalk.client.ui;

import cn.ltalk.client.ContactProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MessageTimelineTest {
    @Test void profileUpdateRepaintsOneAvatarAndNicknameForAlreadyDisplayedMessages() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MessageTimeline timeline = new MessageTimeline();
            var entries = List.of(message("bob", "旧昵称", "第一条"), message("bob", "旧昵称", "第二条"));
            AtomicReference<ContactProfile> profile = new AtomicReference<>();
            timeline.setContactProfiles(user -> profile.get());
            timeline.setSize(420, 380);
            timeline.showEntries(entries, "alice", "", true);
            layout(timeline); layout(timeline);
            assertEquals(List.of("旧昵称"), visibleSenders(timeline));
            BufferedImage photo = new BufferedImage(144, 144, BufferedImage.TYPE_INT_RGB);
            Color imageColor = new Color(225, 35, 70);
            Graphics2D imageGraphics = photo.createGraphics();
            imageGraphics.setColor(imageColor); imageGraphics.fillRect(0, 0, 144, 144); imageGraphics.dispose();
            profile.set(new ContactProfile("bob", "新昵称", photo, 1));
            timeline.showEntries(entries, "alice", "", true);
            layout(timeline); layout(timeline);
            assertEquals(List.of("新昵称"), visibleSenders(timeline));
            BufferedImage painted = paint(timeline.getViewport());
            List<Integer> tops = avatarTops(painted);
            assertEquals(1, tops.size());
            assertEquals(imageColor.getRGB(), painted.getRGB(28, tops.get(0) + 16));
            save(painted, "message-profile-avatar.png");
        });
    }
    @Test void groupHeadUsesNicknameAndGroupingSurvivesSearchAndRemoval() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MessageTimeline timeline = new MessageTimeline();
            var first = message("bob", "同名", "开头");
            var second = message("bob", "同名", "命中一");
            var another = message("carol", "同名", "隔开");
            var third = message("bob", "同名", "命中二");
            var own = message("alice", "我的昵称", "我的消息");
            var fallback = message("legacy", null, "旧服务端");
            var nextDay = new ConversationStore.Entry(ConversationStore.EntryKind.MESSAGE, "legacy", null, "隔天",
                    Instant.EPOCH.plusSeconds(86400), null, null);
            var notice = new ConversationStore.Entry(ConversationStore.EntryKind.SYSTEM, "", "", "提示", nextDay.time());
            var afterNotice = new ConversationStore.Entry(ConversationStore.EntryKind.MESSAGE, "legacy", null, "提示后",
                    nextDay.time(), null, null);
            timeline.showEntries(List.of(first, second, another, third, own, fallback, nextDay, notice, afterNotice), "alice", "", true);
            assertEquals(List.of("同名", "同名", "同名", "legacy", "legacy", "legacy"), visibleSenders(timeline));
            assertTrue(timeline.renderedText().contains("[我] 我的消息"));
            timeline.showEntries(List.of(first, second, another, third), "alice", "命中", true);
            assertEquals(List.of("同名", "同名"), visibleSenders(timeline), "Filtering an intervening sender must not merge separate runs");
            timeline.showEntries(List.of(first, second, another, third), "alice", "同名", true);
            assertTrue(timeline.renderedText().contains("隔开"));
            timeline.showEntries(List.of(second, third), "alice", "", true);
            assertEquals(List.of("同名"), visibleSenders(timeline), "First surviving row must acquire the sender label");
        });
    }

    @Test void avatarFollowsTheVisibleRunAndDisappearsAfterItsLastBubbleScrollsOff() throws Exception {
        AtomicReference<MessageTimeline> ref = new AtomicReference<>();
        List<ConversationStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 8; i++) entries.add(message("bob", "小波", "连续消息 " + i));
        for (int i = 0; i < 8; i++) entries.add(message("alice", "我", "后续消息 " + i));
        entries.add(8, message("carol", "小陈", "下一组"));
        SwingUtilities.invokeAndWait(() -> {
            MessageTimeline timeline = new MessageTimeline(); ref.set(timeline);
            timeline.setSize(420, 680); timeline.showEntries(entries.subList(0, 8), "alice", "", true); layout(timeline); layout(timeline);
        });
        SwingUtilities.invokeAndWait(() -> {
            MessageTimeline timeline = ref.get(); JViewport view = timeline.getViewport();
            List<JTextPane> bodies = new ArrayList<>(); collect(timeline, bodies);
            Component lastBobRow = bodies.get(7).getParent().getParent();
            int runBottom = lastBobRow.getY() + lastBobRow.getHeight();
            view.setViewPosition(new Point(0, 0));
            BufferedImage complete = paint(view);
            assertEquals(List.of(runBottom - 38), avatarTops(complete));
            save(complete, "message-run-complete.png");
        });
        SwingUtilities.invokeAndWait(() -> {
            MessageTimeline timeline = ref.get(); JViewport view = timeline.getViewport();
            timeline.setSize(360, 240); timeline.showEntries(entries, "alice", "", true);
            layout(timeline); layout(timeline);
        });
        SwingUtilities.invokeAndWait(() -> {
            MessageTimeline timeline = ref.get(); JViewport view = timeline.getViewport();
            List<JTextPane> bodies = new ArrayList<>(); collect(timeline, bodies);
            Component lastBobRow = bodies.get(7).getParent().getParent();
            int runBottom = lastBobRow.getY() + lastBobRow.getHeight();

            view.setViewPosition(new Point(0, 80));
            assertTrue(runBottom > view.getViewRect().y + view.getHeight());
            BufferedImage clipped = paint(view);
            assertEquals(List.of(view.getHeight() - 38), avatarTops(clipped));
            save(clipped, "message-run-pinned.png");

            int nextTop = bodies.get(8).getParent().getParent().getY();
            view.setViewPosition(new Point(0, nextTop - view.getHeight()));
            assertEquals(1, avatarTops(paint(view)).size(), "The next avatar must disappear with its bubble at the bottom edge");
            for (int visibleHeight : new int[]{4, 12, 56}) {
                int bubbleTop = view.getHeight() - visibleHeight;
                view.setViewPosition(new Point(0, nextTop - bubbleTop));
                BufferedImage edge = paint(view);
                List<Integer> tops = avatarTops(edge);
                assertEquals(2, tops.size(), "Two visible groups keep separate avatars at the viewport edge");
                assertTrue(tops.get(1) >= bubbleTop, "The avatar must not extend above the first visible pixels of its group");
                if (visibleHeight == 12) save(edge, "message-avatar-clipped-at-bottom.png");
            }
            view.setViewPosition(new Point(0, runBottom - 12));
            BufferedImage tail = paint(view);
            assertEquals(0, avatarTops(tail).get(0), "The visible tail keeps its avatar");
            for (int y = 12; y < 32; y++) {
                assertEquals(Color.WHITE.getRGB(), tail.getRGB(28, y), "The avatar must not extend below the last visible pixels of its group");
            }
            view.setViewPosition(new Point(0, runBottom));
            assertEquals(1, avatarTops(paint(view)).size(), "Only the next sender's avatar remains");
            view.setViewPosition(new Point(0, 80));
            assertEquals(80, view.getViewPosition().y);
            entries.add(message("alice", null, "新消息"));
            timeline.showEntries(entries, "alice", "", true, true); layout(timeline); layout(timeline);
            assertEquals(80, view.getViewPosition().y, "Immediate layout must preserve position");
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(80, ref.get().getViewport().getViewPosition().y));
    }

    private static ConversationStore.Entry message(String user, String nickname, String content) {
        return new ConversationStore.Entry(ConversationStore.EntryKind.MESSAGE, user, null, content, Instant.EPOCH, null, nickname);
    }
    private static List<String> visibleSenders(Container container) {
        List<String> result = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (!component.isVisible()) continue;
            if (component instanceof JLabel label && !label.getText().matches("[0-9]{2}:[0-9]{2}")) result.add(label.getText());
            else if (component instanceof Container nested) result.addAll(visibleSenders(nested));
        }
        return result;
    }
    private static BufferedImage paint(JViewport view) {
        BufferedImage image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, image.getWidth(), image.getHeight());
        view.printAll(g); g.dispose();
        return image;
    }
    private static List<Integer> avatarTops(BufferedImage image) {
        List<Integer> tops = new ArrayList<>();
        boolean previous = false;
        for (int y = 0; y < image.getHeight(); y++) {
            boolean colored = false;
            for (int x = 12; x < 44 && !colored; x++) {
                Color pixel = new Color(image.getRGB(x, y));
                colored = Math.max(pixel.getRed(), Math.max(pixel.getGreen(), pixel.getBlue()))
                        - Math.min(pixel.getRed(), Math.min(pixel.getGreen(), pixel.getBlue())) > 25;
            }
            if (colored && !previous) tops.add(y);
            previous = colored;
        }
        return tops;
    }
    private static void save(BufferedImage image, String name) {
        try {
            Path directory = Files.createDirectories(Path.of("target", "ui-preview"));
            ImageIO.write(image, "png", directory.resolve(name).toFile());
        } catch (IOException ex) { throw new AssertionError(ex); }
    }
    @Test void equalValuedEventsRemainSeparateWhenAppendingAndSwitchingIdentity() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MessageTimeline timeline = new MessageTimeline();
            var first = new ConversationStore.Entry(ConversationStore.EntryKind.MESSAGE, "bob", null, "相同内容", Instant.EPOCH);
            var second = new ConversationStore.Entry(ConversationStore.EntryKind.MESSAGE, "bob", null, "相同内容", Instant.EPOCH);
            timeline.showEntries(List.of(first), "alice", "", true);
            timeline.showEntries(List.of(first, second), "alice", "", true);
            List<JTextPane> bodies = new ArrayList<>(); collect(timeline, bodies);
            assertEquals(2, bodies.size()); assertEquals(2, timeline.renderedText().split("相同内容", -1).length - 1);
            timeline.showEntries(List.of(first), "bob", "", true);
            assertTrue(timeline.renderedText().startsWith("[我]"));
        });
    }
    @Test @Timeout(15)
    void longUnbrokenPlainTextWrapsAndNewMessagesScrollToBottom() throws Exception {
        AtomicReference<MessageTimeline> ref = new AtomicReference<>();
        String literal = "<html><b>纯文本</b>🙂\n" + "x".repeat(1900);
        SwingUtilities.invokeAndWait(() -> {
            MessageTimeline timeline = new MessageTimeline(); ref.set(timeline);
            List<ConversationStore.Entry> entries = new ArrayList<>();
            for (int i = 0; i < 20; i++) entries.add(new ConversationStore.Entry(ConversationStore.EntryKind.MESSAGE,
                    i % 2 == 0 ? "alice" : "bob", null, "消息 " + i, Instant.now()));
            entries.add(new ConversationStore.Entry(ConversationStore.EntryKind.MESSAGE, "bob", null, literal, Instant.now()));
            timeline.setSize(420, 620); timeline.showEntries(entries, "alice", "", true);
            layout(timeline); layout(timeline); timeline.scrollToBottom();
        });
        SwingUtilities.invokeAndWait(() -> {
            MessageTimeline timeline = ref.get();
            JScrollBar bar = timeline.getVerticalScrollBar();
            assertTrue(bar.getMaximum() > bar.getVisibleAmount());
            assertTrue(bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 1, "Latest message must be visible after rendering");
            List<JTextPane> bodies = new ArrayList<>(); collect(timeline, bodies);
            JTextPane longMessage = bodies.stream().filter(p -> documentText(p).equals(literal)).findFirst().orElseThrow();
            assertFalse(longMessage.isEditable()); assertTrue(longMessage.getHeight() > 100, "A long token must wrap rather than overflow horizontally");
            assertTrue(longMessage.getWidth() < 420); assertTrue(timeline.renderedText().contains("<html><b>纯文本</b>"));
        });
    }
    private static String documentText(JTextPane pane) {
        try { return pane.getDocument().getText(0, pane.getDocument().getLength()); }
        catch (javax.swing.text.BadLocationException ex) { throw new AssertionError(ex); }
    }
    private static void layout(Container container) {
        container.doLayout(); for (Component c : container.getComponents()) if (c instanceof Container nested) layout(nested);
    }
    private static void collect(Container container, List<JTextPane> result) {
        for (Component c : container.getComponents()) {
            if (c instanceof JTextPane pane) result.add(pane);
            else if (c instanceof Container nested) collect(nested, result);
        }
    }
}
