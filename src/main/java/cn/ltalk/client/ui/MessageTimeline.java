package cn.ltalk.client.ui;

import cn.ltalk.client.ContactProfile;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/** Selectable, plain-text bubbles. Layout measures against viewport width, including narrow windows. */
public final class MessageTimeline extends JScrollPane {
    private static final int AVATAR_SIZE = 32, AVATAR_X = 12, AVATAR_INSET = 6;
    private final Column column = new Column();
    private String renderedText = "";
    private int renderGeneration;
    private boolean scrollingToBottom;
    private Map<ConversationStore.Entry, Component> renderedRows = new IdentityHashMap<>();
    private String renderedSelf = "";
    private ThemePalette renderedPalette;
    private Function<String, ContactProfile> profiles = userId -> null;
    private Consumer<ConversationStore.Entry> onDelete = entry -> { };
    private Consumer<ConversationStore.Entry> onRecover = entry -> { };
    private boolean deletionEnabled;
    public void deletion(boolean enabled, Consumer<ConversationStore.Entry> action) { deletionEnabled = enabled; onDelete = action; }
    public void onRecover(Consumer<ConversationStore.Entry> action) { onRecover = action; }

    public MessageTimeline() {
        super(); setName("timeline"); setViewportView(column); ChatTheme.scroll(this, true);
        getAccessibleContext().setAccessibleName("聊天消息");
        // An avatar's position depends on the viewport, so shifted copies of old pixels are invalid.
        getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { column.revalidate(); }
        });
    }
    public String renderedText() { return renderedText; }
    public void setContactProfiles(Function<String, ContactProfile> lookup) { profiles = lookup; }

    public void showEntries(List<ConversationStore.Entry> entries, String self, String query, boolean connected) {
        showEntries(entries, self, query, connected, false);
    }

    public void showEntries(List<ConversationStore.Entry> entries, String self, String query, boolean connected,
                            boolean keepReadingPosition) {
        Rectangle view = getViewport().getViewRect();
        boolean follow = !keepReadingPosition || scrollingToBottom || view.y + view.height >= column.getHeight() - 1;
        ThemePalette palette = ChatTheme.palette(this);
        Map<ConversationStore.Entry, Component> previous = renderedSelf.equals(self) && palette.equals(renderedPalette) ? renderedRows : Map.of();
        renderedPalette = palette;
        renderedSelf = self; renderedRows = new IdentityHashMap<>();
        column.removeAll(); column.avatarGroups.clear(); renderGeneration++;
        String needle = query.strip().toLowerCase(Locale.ROOT);
        StringBuilder plain = new StringBuilder();
        renderEntries(entries, self, needle, palette, previous, plain);
        if (column.getComponentCount() == 0) {
            column.add(new EmptyState(needle.isEmpty() ? "从一句问候开始" : "没有找到消息",
                    needle.isEmpty() ? connected ? "发送消息，与伙伴保持联系" : "连接到聊天室，开始你的第一次对话" : "试试其他关键词", palette));
        }
        renderedText = plain.toString();
        column.revalidate(); column.repaint();
        if (follow) scrollToBottom();
    }

    private void renderEntries(List<ConversationStore.Entry> entries, String self, String needle, ThemePalette palette,
                               Map<ConversationStore.Entry, Component> previous, StringBuilder plain) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone), previousDate = null, shownDate = null;
        ConversationStore.Entry prior = null;
        boolean shownInRun = false;
        AvatarGroup avatar = null;
        for (ConversationStore.Entry entry : entries) {
            LocalDate date = entry.time().atZone(zone).toLocalDate();
            boolean message = isChat(entry);
            if (!message || prior == null || !isChat(prior)
                    || !entry.from().equals(prior.from()) || !date.equals(previousDate)) {
                shownInRun = false;
                avatar = null;
            }
            prior = entry;
            previousDate = date;
            ContactProfile profile = message ? profiles.apply(entry.from()) : null;
            String displayName = profile == null ? entry.displayName() : profile.displayName();
            if (!matches(entry, displayName, needle)) continue;
            if (!date.equals(shownDate)) {
                column.add(new Pill(date.equals(today) ? "今天" : date.toString(), false, palette));
                shownDate = date;
            }
            Component row = previous.get(entry);
            if (message) {
                boolean own = entry.from().equals(self);
                BubbleRow bubble = row == null ? new BubbleRow(entry, own, palette,
                        () -> onDelete.accept(entry), () -> onRecover.accept(entry)) : (BubbleRow) row;
                bubble.setDisplayName(displayName);
                bubble.startsRun(!shownInRun);
                bubble.delete.setEnabled(deletionEnabled && entry.messageId() != null);
                if (!own) {
                    if (avatar == null) {
                        avatar = new AvatarGroup(bubble, ProfileAvatar.contact(profile, displayName, AVATAR_SIZE));
                        column.avatarGroups.add(avatar);
                    }
                    avatar.last = bubble;
                }
                shownInRun = true;
                row = bubble;
                plain.append(own ? "[我] " : "[" + displayName + "] ");
            } else if (row == null) {
                row = new Pill(entry.content(), entry.kind() == ConversationStore.EntryKind.ERROR, palette);
            }
            renderedRows.put(entry, row);
            column.add(row);
            plain.append(entry.content()).append('\n');
        }
    }

    private static boolean isChat(ConversationStore.Entry entry) {
        return entry.kind() == ConversationStore.EntryKind.MESSAGE || entry.kind() == ConversationStore.EntryKind.OUTGOING;
    }

    private static boolean matches(ConversationStore.Entry entry, String displayName, String needle) {
        return needle.isEmpty() || entry.content().toLowerCase(Locale.ROOT).contains(needle)
                || entry.from().toLowerCase(Locale.ROOT).contains(needle)
                || displayName.toLowerCase(Locale.ROOT).contains(needle);
    }

    public void scrollToBottom() {
        scrollingToBottom = true;
        int generation = renderGeneration;
        SwingUtilities.invokeLater(() -> {
            if (generation != renderGeneration) return;
            scrollingToBottom = false;
            getVerticalScrollBar().setValue(getVerticalScrollBar().getMaximum());
        });
    }

    private static final class AvatarGroup {
        final BubbleRow first;
        final Icon icon;
        BubbleRow last;
        AvatarGroup(BubbleRow first, Icon icon) { this.first = first; this.last = first; this.icon = icon; }
        int bottom() { return last.getY() + last.getHeight(); }
    }

    private final class Column extends JPanel implements Scrollable {
        private final List<AvatarGroup> avatarGroups = new ArrayList<>();
        Column() {
            setOpaque(false);
            setLayout(new LayoutManager() {
                @Override public void addLayoutComponent(String name, Component comp) { }
                @Override public void removeLayoutComponent(Component comp) { }
                @Override public Dimension preferredLayoutSize(Container parent) {
                    int width = Math.max(200, parent.getWidth());
                    int height = 40;
                    for (Component component : parent.getComponents()) height += measured(component, width) + gapBefore(component);
                    return new Dimension(width, height);
                }
                @Override public Dimension minimumLayoutSize(Container p) { return new Dimension(150, 100); }
                @Override public void layoutContainer(Container parent) {
                    int width = parent.getWidth(); int y = 8;
                    for (Component component : parent.getComponents()) {
                        y += gapBefore(component);
                        int height = measured(component, width); component.setBounds(0, y, width, height);
                        if (component instanceof Container container) container.doLayout();
                        y += height;
                    }
                }
                private int gapBefore(Component component) {
                    return component instanceof BubbleRow row && !row.startsRun ? 3 : 8;
                }
                private int measured(Component component, int width) {
                    if (component instanceof BubbleRow row) return row.measure(width).height;
                    if (component instanceof Pill pill) return pill.measure(width).height;
                    return component.getPreferredSize().height;
                }
            });
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Rectangle view = getViewport().getViewRect();
            int viewBottom = view.y + view.height;
            // Skip groups already above the viewport even on long histories.
            int low = 0, high = avatarGroups.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (avatarGroups.get(mid).bottom() <= view.y) low = mid + 1;
                else high = mid;
            }
            for (int i = low; i < avatarGroups.size(); i++) {
                AvatarGroup group = avatarGroups.get(i);
                int groupTop = group.first.getY();
                if (groupTop >= viewBottom) break;
                int bottom = Math.min(group.bottom(), viewBottom);
                int visibleHeight = bottom - Math.max(groupTop, view.y);
                // Keep the avatar within the group and let the viewport clip both at its edges.
                int inset = Math.min(AVATAR_INSET, Math.max(0, visibleHeight - AVATAR_SIZE));
                int y = Math.max(groupTop, bottom - AVATAR_SIZE - inset);
                group.icon.paintIcon(this, graphics, AVATAR_X, y);
            }
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(600, 450); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return r.height - 24; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getPreferredSize().height < getParent().getHeight(); }
    }

    private static final class BubbleRow extends JPanel {
        private final boolean own;
        private final ChatTheme.RoundedPanel bubble;
        private final MessageText body;
        private final JLabel sender;
        private final JMenuItem delete;
        private final JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        private Dimension measured;
        private int measuredWidth = -1;
        private boolean startsRun = true;
        BubbleRow(ConversationStore.Entry entry, boolean own, ThemePalette palette, Runnable onDelete, Runnable onRecover) {
            super(null); this.own = own; setOpaque(false);
            bubble = new ChatTheme.RoundedPanel(new BorderLayout(0, 3), 20, own ? palette.outgoing() : palette.surface(), true);
            bubble.setBorder(new EmptyBorder(9, 13, 7, 13));
            body = new MessageText(entry.content(), palette);
            ChatPopupMenu actions = new ChatPopupMenu(body, "聊天记录");
            delete = actions.action("deleteMessage", "删除这条记录（仅本人）", LineIcon.Kind.CLOSE, onDelete);
            body.setComponentPopupMenu(actions); bubble.setComponentPopupMenu(actions); setComponentPopupMenu(actions);
            bubble.add(body, BorderLayout.CENTER);
            sender = ChatTheme.label("", 12, avatarColor(entry.from(), palette.dark()), true);
            if (!own) bubble.add(sender, BorderLayout.NORTH);
            String time = DateTimeFormatter.ofPattern("HH:mm").format(entry.time().atZone(ZoneId.systemDefault()));
            ConversationStore.SendStatus status = entry.sendStatus();
            String result = status == null ? "" : switch (status) {
                case SENDING -> " · 发送中";
                case CONFIRMED -> " · 已确认";
                case FAILED -> " · 发送失败";
                case UNCONFIRMED -> " · 结果待确认";
            };
            JLabel stamp = ChatTheme.label(time + result, 10,
                    status == ConversationStore.SendStatus.FAILED ? palette.danger() : palette.muted(), false);
            stamp.setName("sendStatus");
            stamp.setToolTipText(status == ConversationStore.SendStatus.CONFIRMED ? "已收到服务端回显" : entry.sendDetail());
            footer.setOpaque(false);
            footer.add(stamp);
            if (status == ConversationStore.SendStatus.FAILED || status == ConversationStore.SendStatus.UNCONFIRMED) {
                JButton recover = ChatTheme.pill("取回正文");
                recover.setName("recoverMessage");
                recover.setFont(ChatTheme.FONT.deriveFont(11f));
                recover.setBorder(new EmptyBorder(2, 5, 2, 5));
                recover.setToolTipText(entry.sendDetail());
                recover.addActionListener(e -> onRecover.run());
                ChatTheme.apply(recover, palette);
                footer.add(recover);
            }
            bubble.add(footer, BorderLayout.SOUTH); add(bubble);
        }
        void setDisplayName(String name) {
            if (name.equals(sender.getText())) return;
            sender.setText(name);
            body.getAccessibleContext().setAccessibleName((own ? "我" : name) + "的消息");
            measured = null;
        }
        void startsRun(boolean value) {
            if (startsRun == value) return;
            startsRun = value;
            sender.setVisible(value);
            measured = null;
        }
        private static Color avatarColor(String name, boolean dark) {
            return Color.getHSBColor(Math.floorMod(name.hashCode(), 360) / 360f, .58f, dark ? .96f : .62f);
        }
        Dimension measure(int availableWidth) {
            if (measured != null && measuredWidth == availableWidth) return measured;
            int maximum = Math.max(120, Math.min(560, availableWidth - 86));
            int natural = Math.max(100, body.naturalWidth() + 34);
            natural = Math.max(natural, footer.getPreferredSize().width + 26);
            boolean showSender = !own && startsRun;
            if (showSender) natural = Math.max(natural, sender.getPreferredSize().width + 26);
            int width = Math.min(maximum, natural);
            body.setSize(Math.max(70, width - 26), Short.MAX_VALUE);
            int height = body.getPreferredSize().height + footer.getPreferredSize().height + 22
                    + (showSender ? sender.getPreferredSize().height + 3 : 0);
            measuredWidth = availableWidth; measured = new Dimension(width, Math.max(52, height)); return measured;
        }
        @Override public void doLayout() {
            Dimension size = measure(getWidth());
            int x = own ? Math.max(8, getWidth() - size.width - 14) : 52;
            bubble.setBounds(x, 0, size.width, getHeight()); bubble.doLayout();
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
            text = new JTextArea();
            if (text.getCaret() instanceof DefaultCaret caret) caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
            text.setText(value); text.setEditable(false); text.setOpaque(false); text.setLineWrap(true); text.setWrapStyleWord(true);
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
