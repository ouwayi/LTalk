package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;

public final class ChatTheme {
    public static final Color BLUE = new Color(51, 144, 236);
    public static final Color INK = new Color(28, 39, 49);
    public static final Color MUTED = new Color(132, 145, 156);
    public static final Color SOFT = new Color(242, 245, 247);
    public static final Color LINE = new Color(232, 237, 240);
    public static final Color OUTGOING = new Color(225, 255, 199);
    public static final Color RED = new Color(230, 92, 100);
    public static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 14);
    private ChatTheme() { }

    public static void install() {
        for (Object key : UIManager.getDefaults().keySet().toArray()) {
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)
                UIManager.put(key, new javax.swing.plaf.FontUIResource(FONT));
        }
        UIManager.put("ToolTip.background", Color.WHITE);
        UIManager.put("ToolTip.foreground", INK);
    }
    public static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    public static JLabel label(String value, int size, Color color, boolean bold) {
        JLabel label = new JLabel(value);
        label.putClientProperty("html.disable", Boolean.TRUE);
        label.setFont(FONT.deriveFont(bold ? Font.BOLD : Font.PLAIN, size)); label.setForeground(color);
        return label;
    }
    public static JButton iconButton(LineIcon.Kind kind, String tooltip) {
        JButton button = new JButton(new LineIcon(kind, MUTED, 22));
        button.setToolTipText(tooltip); button.getAccessibleContext().setAccessibleName(tooltip);
        button.setPreferredSize(new Dimension(40, 40));
        button.setBorder(new EmptyBorder(8, 8, 8, 8)); button.setContentAreaFilled(false); button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); button.setRolloverEnabled(true);
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override public void paint(Graphics graphics, JComponent component) {
                Graphics2D g = (Graphics2D) graphics.create(); quality(g);
                if (button.getModel().isRollover() || button.hasFocus()) {
                    g.setColor(SOFT); g.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), 22, 22);
                }
                g.dispose(); super.paint(graphics, component);
            }
        });
        return button;
    }
    public static JButton primary(String title) {
        JButton button = new JButton(title);
        button.setFont(FONT.deriveFont(Font.BOLD)); button.setForeground(Color.WHITE);
        button.setBorder(new EmptyBorder(10, 18, 10, 18)); button.setFocusPainted(false); button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override public void paint(Graphics graphics, JComponent component) {
                Graphics2D g = (Graphics2D) graphics.create(); quality(g);
                g.setColor(!button.isEnabled() ? new Color(179, 202, 221) : button.getModel().isRollover() ? BLUE.darker() : BLUE);
                g.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), 20, 20); g.dispose(); super.paint(graphics, component);
            }
        });
        return button;
    }
    public static JTextField field(String value, String name) {
        JTextField field = new JTextField(value) {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Object hint = getClientProperty("placeholder");
                if (getText().isEmpty() && hint instanceof String text) {
                    Graphics2D g = (Graphics2D) graphics.create(); quality(g); g.setColor(MUTED); g.setFont(getFont());
                    g.drawString(text, getInsets().left + 1, (getHeight() - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent()); g.dispose();
                }
            }
        };
        field.setName(name); field.setFont(FONT); field.setForeground(INK);
        field.setBackground(SOFT); field.setBorder(new EmptyBorder(10, 12, 10, 12));
        return field;
    }
    public static void scroll(JScrollPane pane, boolean transparent) {
        pane.setBorder(null); pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setOpaque(!transparent); pane.getViewport().setOpaque(!transparent);
        if (!transparent) pane.getViewport().setBackground(Color.WHITE);
        JScrollBar bar = pane.getVerticalScrollBar(); bar.setUnitIncrement(24); bar.setPreferredSize(new Dimension(7, 0));
        bar.setUI(new BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return zero(); }
            @Override protected JButton createIncreaseButton(int orientation) { return zero(); }
            private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension()); return b; }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }
            @Override protected void paintThumb(Graphics graphics, JComponent c, Rectangle r) {
                Graphics2D g = (Graphics2D) graphics.create(); quality(g);
                g.setColor(new Color(100, 124, 114, isThumbRollover() ? 135 : 70));
                g.fillRoundRect(r.x + 1, r.y, Math.max(3, r.width - 2), r.height, 7, 7); g.dispose();
            }
        });
        bar.setOpaque(false);
    }

    public static class RoundedPanel extends JPanel {
        private final int radius;
        private final boolean shadow;
        public RoundedPanel(LayoutManager layout, int radius, Color color, boolean shadow) {
            super(layout); this.radius = radius; this.shadow = shadow; setBackground(color); setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); quality(g);
            if (shadow) { g.setColor(new Color(30, 62, 40, 20)); g.fillRoundRect(0, 2, getWidth(), getHeight() - 2, radius, radius); }
            g.setColor(getBackground()); g.fillRoundRect(0, 0, getWidth(), getHeight() - (shadow ? 2 : 0), radius, radius);
            g.dispose(); super.paintComponent(graphics);
        }
    }
}
