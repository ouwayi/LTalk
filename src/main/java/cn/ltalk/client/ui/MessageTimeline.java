package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.IdentityHashMap;

/** Selectable, plain-text bubbles. Layout measures against viewport width, including narrow windows. */
public final class MessageTimeline extends JScrollPane {
    private final Column column = new Column();
    private String renderedText = "";
    private int renderGeneration;
    private Map<ConversationStore.Entry, Component> renderedRows = new IdentityHashMap<>();
    private String renderedSelf = "";
    private ThemePalette renderedPalette;
    private java.util.function.Consumer<ConversationStore.Entry> onDelete = entry -> { };
    private boolean deletionEnabled;
    public void deletion(boolean enabled, java.util.function.Consumer<ConversationStore.Entry> action) { deletionEnabled = enabled; onDelete = action; }

    public MessageTimeline() {
        super(); setName("timeline"); setViewportView(column); ChatTheme.scroll(this, true);
        getAccessibleContext().setAccessibleName("聊天消息");
        getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { column.revalidate(); }
        });
    }
    public String renderedText() { return renderedText; }

    public void showEntries(List<ConversationStore.Entry> entries, String self, String query, boolean connected) {
        ThemePalette palette = ChatTheme.palette(this);
        Map<ConversationStore.Entry, Component> previous = renderedSelf.equals(self) && palette.equals(renderedPalette) ? renderedRows : Map.of();
        renderedPalette = palette;
        renderedSelf = self; renderedRows = new IdentityHashMap<>();
        column.removeAll(); renderGeneration++;
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<ConversationStore.Entry> shown = entries.stream()
                .filter(e -> needle.isEmpty() || e.content().toLowerCase(Locale.ROOT).contains(needle)
                        || e.from().toLowerCase(Locale.ROOT).contains(needle)).toList();
        StringBuilder plain = new StringBuilder();
        if (shown.isEmpty()) {
            column.add(new EmptyState(needle.isEmpty() ? "从一句问候开始" : "没有找到消息",
                    needle.isEmpty() ? connected ? "发送消息，与伙伴保持联系" : "连接到聊天室，开始你的第一次对话" : "试试其他关键词", palette));
        } else {
            LocalDate previousDate = null;
            for (ConversationStore.Entry entry : shown) {
                LocalDate date = entry.time().atZone(ZoneId.systemDefault()).toLocalDate();
                if (!date.equals(previousDate)) { column.add(new Pill(date.equals(LocalDate.now()) ? "今天" : date.toString(), false, palette)); previousDate = date; }
                if (entry.kind() == ConversationStore.EntryKind.MESSAGE) {
                    boolean own = entry.from().equals(self);
                    Component row = previous.get(entry);
                    if (row == null) row = new BubbleRow(entry, own, palette, () -> onDelete.accept(entry));
                    ((BubbleRow) row).delete.setEnabled(deletionEnabled && entry.messageId() != null);
                    renderedRows.put(entry, row); column.add(row);
                    plain.append(own ? "[我] " : "[" + entry.from() + "] ");
                } else {
                    Component row = previous.get(entry);
                    if (row == null) row = new Pill(entry.content(), entry.kind() == ConversationStore.EntryKind.ERROR, palette);
                    renderedRows.put(entry, row); column.add(row);
                }
                plain.append(entry.content()).append('\n');
            }
        }
        renderedText = plain.toString();
        column.revalidate(); column.repaint(); scrollToBottom();
    }

    public void scrollToBottom() {
        int generation = renderGeneration;
        SwingUtilities.invokeLater(() -> {
            if (generation != renderGeneration) return;
            getVerticalScrollBar().setValue(getVerticalScrollBar().getMaximum());
        });
    }

    private static final class Column extends JPanel implements Scrollable {
        Column() {
            setOpaque(false);
            setLayout(new LayoutManager() {
                @Override public void addLayoutComponent(String name, Component comp) { }
                @Override public void removeLayoutComponent(Component comp) { }
                @Override public Dimension preferredLayoutSize(Container parent) {
                    int width = Math.max(200, parent.getWidth());
                    int height = 20;
                    for (Component component : parent.getComponents()) height += measured(component, width) + 8;
                    return new Dimension(width, height + 20);
                }
                @Override public Dimension minimumLayoutSize(Container p) { return new Dimension(150, 100); }
                @Override public void layoutContainer(Container parent) {
                    int width = parent.getWidth(); int y = 16;
                    for (Component component : parent.getComponents()) {
                        int height = measured(component, width); component.setBounds(0, y, width, height);
                        if (component instanceof Container container) container.doLayout();
                        y += height + 8;
                    }
                }
                private int measured(Component component, int width) {
                    if (component instanceof BubbleRow row) return row.measure(width).height;
                    if (component instanceof Pill pill) return pill.measure(width).height;
                    return component.getPreferredSize().height;
                }
            });
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(600, 450); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return r.height - 24; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getPreferredSize().height < getParent().getHeight(); }
    }

    private static final class BubbleRow extends JPanel {
        private final ConversationStore.Entry entry;
        private final boolean own;
        private final ChatTheme.RoundedPanel bubble;
        private final MessageText body;
        private final JLabel sender;
        private final JMenuItem delete;
        private Dimension measured;
        private int measuredWidth = -1;
        BubbleRow(ConversationStore.Entry entry, boolean own, ThemePalette palette, Runnable onDelete) {
            super(null); this.entry = entry; this.own = own; setOpaque(false);
            bubble = new ChatTheme.RoundedPanel(new BorderLayout(0, 3), 20, own ? palette.outgoing() : palette.surface(), true);
            bubble.setBorder(new EmptyBorder(9, 13, 7, 13));
            body = new MessageText(entry.content(), palette);
            ChatPopupMenu actions = new ChatPopupMenu(body, "聊天记录");
            delete = actions.action("deleteMessage", "删除这条记录（仅本人）", LineIcon.Kind.CLOSE, onDelete);
            body.setComponentPopupMenu(actions); bubble.setComponentPopupMenu(actions); setComponentPopupMenu(actions);
            body.getAccessibleContext().setAccessibleName((own ? "我" : entry.from()) + "的消息");
            bubble.add(body, BorderLayout.CENTER);
            sender = ChatTheme.label(entry.from(), 12, avatarColor(entry.from(), palette.dark()), true);
            if (!own) bubble.add(sender, BorderLayout.NORTH);
            String time = DateTimeFormatter.ofPattern("HH:mm").format(entry.time().atZone(ZoneId.systemDefault()));
            JLabel stamp = ChatTheme.label(time, 10, palette.muted(), false);
            if (own) { stamp.setIcon(new LineIcon(LineIcon.Kind.CHECK, palette.muted(), 12)); stamp.setHorizontalTextPosition(SwingConstants.LEFT); }
            stamp.setHorizontalAlignment(SwingConstants.RIGHT);
            if (own) stamp.setToolTipText("服务端已回显，不代表对方已读");
            bubble.add(stamp, BorderLayout.SOUTH); add(bubble);
        }
        private static Color avatarColor(String name, boolean dark) {
            return Color.getHSBColor(Math.floorMod(name.hashCode(), 360) / 360f, .58f, dark ? .96f : .62f);
        }
        Dimension measure(int availableWidth) {
            if (measured != null && measuredWidth == availableWidth) return measured;
            int maximum = Math.max(120, Math.min(560, availableWidth - 86));
            int natural = Math.max(100, body.naturalWidth() + 34);
            if (!own) natural = Math.max(natural, sender.getPreferredSize().width + 26);
            int width = Math.min(maximum, natural);
            body.setSize(Math.max(70, width - 26), Short.MAX_VALUE);
            int height = body.getPreferredSize().height + (own ? 0 : sender.getPreferredSize().height + 3) + 36;
            measuredWidth = availableWidth; measured = new Dimension(width, Math.max(58, height)); return measured;
        }
        @Override public void doLayout() {
            Dimension size = measure(getWidth());
            int x = own ? Math.max(8, getWidth() - size.width - 14) : 52;
            bubble.setBounds(x, 0, size.width, getHeight()); bubble.doLayout();
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (!own) new Avatar(entry.from(), false, 32).paintIcon(this, graphics, 12, Math.max(0, getHeight() - 38));
        }
    }

    private static final class Pill extends JPanel {
        private final JTextArea text;
        private final boolean error;
        private final ThemePalette palette;
        private Dimension measured;
        private int measuredWidth = -1;
        Pill(String value, boolean error, ThemePalette palette) {
            super(null); setOpaque(false); this.error = error; this.palette = palette;
            text = new JTextArea(value); text.setEditable(false); text.setOpaque(false); text.setLineWrap(true); text.setWrapStyleWord(true);
            text.setFont(ChatTheme.FONT.deriveFont(11f)); text.setForeground(error ? palette.danger() : palette.muted());
            text.setBorder(null); add(text);
        }
        Dimension measure(int width) {
            if (measured != null && measuredWidth == width) return measured;
            int natural = text.getFontMetrics(text.getFont()).stringWidth(text.getText()) + 24;
            int w = Math.max(70, Math.min(Math.max(100, width - 40), Math.min(620, natural)));
            text.setSize(w - 20, Short.MAX_VALUE);
            measuredWidth = width; measured = new Dimension(w, text.getPreferredSize().height + 12); return measured;
        }
        @Override public void doLayout() {
            Dimension size = measure(getWidth()); text.setBounds((getWidth() - size.width) / 2 + 10, 5, size.width - 20, size.height - 10);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
            Dimension size = measured == null ? measure(getWidth()) : measured;
            Color base = error ? palette.errorSurface() : palette.surface();
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 235));
            g.fillRoundRect((getWidth() - size.width) / 2, 0, size.width, size.height, 18, 18); g.dispose();
        }
    }
    private static final class EmptyState extends JPanel {
        EmptyState(String title, String subtitle, ThemePalette palette) {
            super(new GridBagLayout()); setOpaque(false); setPreferredSize(new Dimension(300, 300));
            ChatTheme.RoundedPanel card = new ChatTheme.RoundedPanel(new GridLayout(3, 1, 0, 10), 24, palette.surface(), true);
            card.setBorder(new EmptyBorder(22, 24, 22, 24));
            JLabel icon = new JLabel(new LineIcon(LineIcon.Kind.SEND, ChatTheme.BLUE, 36), SwingConstants.CENTER);
            JLabel heading = ChatTheme.label(title, 19, palette.text(), true); heading.setHorizontalAlignment(SwingConstants.CENTER);
            JLabel detail = ChatTheme.label(subtitle, 11, palette.muted(), false); detail.setHorizontalAlignment(SwingConstants.CENTER);
            card.add(icon); card.add(heading); card.add(detail); add(card);
        }
    }
}
