package cn.ltalk.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ConversationList extends JList<ConversationStore.Summary> {
    private int hover = -1;
    public ConversationList(DefaultListModel<ConversationStore.Summary> model) {
        super(model); setName("conversations"); setFixedCellHeight(84); setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ChatTheme.surface(this); setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8)); setCellRenderer(new Cell());
        ToolTipManager.sharedInstance().registerComponent(this);
        getAccessibleContext().setAccessibleName("会话列表"); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent event) {
                int index = locationToIndex(event.getPoint());
                if (index >= 0 && !getCellBounds(index, index).contains(event.getPoint())) index = -1;
                if (hover != index) { hover = index; repaint(); }
            }
        });
        addMouseListener(new MouseAdapter() { @Override public void mouseExited(MouseEvent e) { hover = -1; repaint(); } });
    }
    @Override public String getToolTipText(MouseEvent event) {
        int index = locationToIndex(event.getPoint());
        if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) return null;
        ConversationId id = getModel().getElementAt(index).id();
        return id.kind() == ConversationId.Kind.PRIVATE ? "@" + id.key() : null;
    }
    public static String ellipsis(FontMetrics fm, String text, int width) {
        if (width <= 0) return "";
        String plain = text.replaceAll("[\\r\\n\\t]+", " ");
        if (fm.stringWidth(plain) <= width) return plain;
        int end = plain.length();
        while (end > 0 && fm.stringWidth(plain.substring(0, end) + "…") > width) end = plain.offsetByCodePoints(end, -1);
        return plain.substring(0, end) + "…";
    }
    private final class Cell extends JPanel implements ListCellRenderer<ConversationStore.Summary> {
        private ConversationStore.Summary value;
        private boolean selected, hovered;
        private ThemePalette palette;
        Cell() { setOpaque(false); }
        @Override public Component getListCellRendererComponent(JList<? extends ConversationStore.Summary> list,
                ConversationStore.Summary value, int index, boolean selected, boolean focus) {
            this.value = value; this.selected = selected; hovered = index == hover; palette = ChatTheme.palette(list);
            getAccessibleContext().setAccessibleName(value.title() + (value.online() ? "，在线" : "，离线") + "，" + value.preview() + "，未读 " + value.unread());
            return this;
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
            int w = getWidth();
            if (selected || hovered) { g.setColor(selected ? ChatTheme.BLUE : palette.hover()); g.fillRoundRect(0, 3, w, 78, 20, 20); }
            Icon avatar = value.id().kind() == ConversationId.Kind.GROUP ? new Avatar(value.title(), true, 50)
                    : ProfileAvatar.contact(value.profile(), value.title(), 50);
            avatar.paintIcon(this, g, 12, 17);
            if (value.id().kind() == ConversationId.Kind.PRIVATE) {
                g.setColor(selected ? ChatTheme.BLUE : palette.surface()); g.fillOval(48, 52, 13, 13);
                g.setColor(value.online() ? new Color(81, 190, 130) : new Color(178, 188, 196)); g.fillOval(50, 54, 9, 9);
            }
            if (value.unread() > 0) { g.setColor(selected ? ChatTheme.BLUE : palette.surface()); g.fillOval(48, 16, 13, 13); g.setColor(palette.danger()); g.fillOval(50, 18, 9, 9); }
            Color secondary = selected ? new Color(229, 242, 255) : palette.muted();
            g.setFont(ChatTheme.FONT.deriveFont(11f)); g.setColor(secondary);
            String time = value.time() == null ? "" : DateTimeFormatter.ofPattern("HH:mm").format(value.time().atZone(ZoneId.systemDefault()));
            g.drawString(time, w - 14 - g.getFontMetrics().stringWidth(time), 28);
            g.setFont(ChatTheme.FONT.deriveFont(Font.BOLD, 14f)); g.setColor(selected ? Color.WHITE : palette.text());
            g.drawString(ellipsis(g.getFontMetrics(), value.title(), w - 132), 74, 32);
            int badgeWidth = 0;
            if (value.unread() > 0) {
                String unread = value.unread() > 99 ? "99+" : Integer.toString(value.unread());
                g.setFont(ChatTheme.FONT.deriveFont(Font.BOLD, 11f)); badgeWidth = Math.max(22, g.getFontMetrics().stringWidth(unread) + 12);
                g.setColor(selected ? Color.WHITE : ChatTheme.BLUE); g.fillRoundRect(w - badgeWidth - 12, 47, badgeWidth, 22, 22, 22);
                g.setColor(selected ? ChatTheme.BLUE : Color.WHITE); g.drawString(unread, w - badgeWidth - 6, 62);
            }
            g.setFont(ChatTheme.FONT.deriveFont(12f)); g.setColor(secondary);
            String preview = value.preview().isBlank() ? value.online() ? "在线 · 点击开始私聊" : "离线 · 暂无消息" : value.preview();
            g.drawString(ellipsis(g.getFontMetrics(), preview, w - 90 - badgeWidth - (badgeWidth > 0 ? 6 : 0)), 74, 57);
            g.dispose();
        }
    }
}
