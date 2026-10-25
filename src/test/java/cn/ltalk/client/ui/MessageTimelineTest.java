package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MessageTimelineTest {
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
