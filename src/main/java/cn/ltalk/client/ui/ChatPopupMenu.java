package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import java.awt.*;

/** Shared popup styling; the standard menu model retains keyboard and disabled-item behavior. */
public final class ChatPopupMenu extends JPopupMenu {
    public ChatPopupMenu(Component owner, String heading) {
        setName("chatPopup"); setUI(new BasicPopupMenuUI()); setOpaque(false);
        setBorder(new EmptyBorder(9, 9, 9, 9));
        ChatTheme.apply(this, ChatTheme.palette(owner));
        if (heading != null && !heading.isBlank()) {
            JLabel title = ChatTheme.label(heading, 11, ChatTheme.MUTED, false);
            title.setBorder(new EmptyBorder(5, 12, 8, 12)); add(title);
            ChatTheme.apply(this, ChatTheme.palette(owner));
        }
    }
    public JMenuItem action(String name, String title, LineIcon.Kind icon, Runnable callback) {
        JMenuItem item = new JMenuItem(title, new LineIcon(icon, ChatTheme.MUTED, 19));
        item.setName(name); item.putClientProperty("html.disable", Boolean.TRUE); item.setFont(ChatTheme.FONT);
        item.setOpaque(false); item.setBorder(new EmptyBorder(0, 0, 0, 0));
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        item.setUI(new BasicMenuItemUI() {
            @Override public Dimension getPreferredSize(JComponent c) {
                return new Dimension(Math.max(258, c.getFontMetrics(c.getFont()).stringWidth(item.getText()) + 74), 42);
            }
            @Override public void paint(Graphics graphics, JComponent c) {
                ThemePalette palette = ChatTheme.palette(item);
                Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
                if (item.isEnabled() && (item.getModel().isArmed() || item.getModel().isSelected())) {
                    g.setColor(palette.hover()); g.fillRoundRect(0, 1, item.getWidth(), item.getHeight() - 2, 12, 12);
                }
                item.getIcon().paintIcon(item, g, 11, (item.getHeight() - item.getIcon().getIconHeight()) / 2);
                g.setFont(item.getFont()); g.setColor(item.isEnabled() ? palette.text() : palette.muted());
                g.drawString(item.getText(), 42, (item.getHeight() - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent());
                g.dispose();
            }
        });
        item.addActionListener(event -> callback.run()); add(item); return item;
    }
    @Override public void addSeparator() {
        add(new JSeparator() {
            @Override public Dimension getPreferredSize() { return new Dimension(1, 12); }
            @Override protected void paintComponent(Graphics g) {
                g.setColor(ChatTheme.palette(this).line()); g.drawLine(10, 6, getWidth() - 10, 6);
            }
        });
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g); ThemePalette palette = ChatTheme.palette(this);
        g.setColor(palette.shadow()); g.fillRoundRect(1, 3, getWidth() - 2, getHeight() - 3, 20, 20);
        g.setColor(palette.surface()); g.fillRoundRect(1, 1, getWidth() - 3, getHeight() - 5, 18, 18);
        g.setColor(palette.line()); g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 5, 18, 18); g.dispose();
    }
}
