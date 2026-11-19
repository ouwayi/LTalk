package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicToggleButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.text.JTextComponent;
import java.awt.*;
import static cn.ltalk.client.ui.ThemePalette.Role;

public final class ChatTheme {
    public static final Color BLUE = new Color(51, 144, 236);
    public static final Color INK = new Color(28, 39, 49);
    public static final Color MUTED = new Color(132, 145, 156);
    public static final Color SOFT = new Color(242, 245, 247);
    public static final Color LINE = new Color(232, 237, 240);
    public static final Color OUTGOING = new Color(225, 255, 199);
    public static final Color RED = new Color(230, 92, 100);
    public static final Font FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 14);
    private static final ThemePalette DEFAULT = ThemePalette.of(AppearanceSettings.defaults());
    private static final String PALETTE = "ltalk.palette", FOREGROUND = "ltalk.foreground", BACKGROUND = "ltalk.background";
    private ChatTheme() { }

    public static ThemePalette palette(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof JComponent c && c.getClientProperty(PALETTE) instanceof ThemePalette p) return p;
        }
        return DEFAULT;
    }
    public static void apply(JComponent root, ThemePalette palette) {
        root.putClientProperty(PALETTE, palette); refresh(root);
    }
    static void refresh(JComponent root) { applyTree(root, palette(root)); root.repaint(); }
    private static void applyTree(Component component, ThemePalette palette) {
        if (component instanceof JComponent c) {
            if (c.getClientProperty(FOREGROUND) instanceof Role role) c.setForeground(palette.color(role));
            if (c.getClientProperty(BACKGROUND) instanceof Role role) c.setBackground(palette.color(role));
            if (c instanceof JTextComponent text) {
                text.setCaretColor(palette.text()); text.setSelectionColor(BLUE); text.setSelectedTextColor(Color.WHITE);
                text.setDisabledTextColor(palette.muted());
            }
        }
        if (component instanceof Container container) for (Component child : container.getComponents()) applyTree(child, palette);
    }
    public static void foreground(JComponent component, Role role) {
        component.putClientProperty(FOREGROUND, role); component.setForeground(palette(component).color(role));
    }
    public static void surface(JComponent component) {
        component.putClientProperty(BACKGROUND, Role.SURFACE); component.setBackground(palette(component).surface());
    }
    private static Role role(Color color) {
        if (color.equals(INK)) return Role.TEXT;
        if (color.equals(MUTED)) return Role.MUTED;
        if (color.equals(BLUE)) return Role.ACCENT;
        if (color.equals(RED)) return Role.DANGER;
        return null;
    }

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
        if (role(color) != null) label.putClientProperty(FOREGROUND, role(color));
        return label;
    }
    static JPanel formHint(JComponent control, String... lines) {
        JPanel hint = new JPanel();
        hint.setLayout(new BoxLayout(hint, BoxLayout.PAGE_AXIS));
        hint.setOpaque(false);
        hint.setName(control.getName() + "Hint");
        for (String line : lines) {
            JLabel text = label(line, 12, INK, false);
            text.setAlignmentX(Component.LEFT_ALIGNMENT);
            hint.add(text);
        }
        control.getAccessibleContext().setAccessibleDescription(String.join(" ", lines));
        return hint;
    }
    public static JButton iconButton(LineIcon.Kind kind, String tooltip) {
        JButton button = new JButton(new LineIcon(kind, MUTED, 22));
        button.setToolTipText(tooltip); button.getAccessibleContext().setAccessibleName(tooltip);
        button.setPreferredSize(new Dimension(40, 40));
        button.setBorder(new EmptyBorder(8, 8, 8, 8)); button.setContentAreaFilled(false); button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); button.setRolloverEnabled(true);
        button.setUI(new TextButtonUI() {
            @Override public void paint(Graphics graphics, JComponent component) {
                Graphics2D g = (Graphics2D) graphics.create(); quality(g);
                if (button.getModel().isRollover() || button.hasFocus()) {
                    g.setColor(palette(button).hover()); g.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), 22, 22);
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
        button.setUI(new TextButtonUI() {
            @Override public void paint(Graphics graphics, JComponent component) {
                Graphics2D g = (Graphics2D) graphics.create(); quality(g);
                g.setColor(!button.isEnabled() ? palette(button).disabled() : button.getModel().isRollover() ? BLUE.darker() : BLUE);
                g.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), 20, 20); g.dispose(); super.paint(graphics, component);
            }
        });
        return button;
    }
    public static JButton pill(String title) {
        JButton button = iconButton(LineIcon.Kind.USERS, title); button.setIcon(null); button.setText(title);
        button.setToolTipText(null);
        button.setPreferredSize(null); button.setFont(FONT.deriveFont(Font.BOLD, 12f));
        button.setBorder(new EmptyBorder(8, 14, 8, 14)); foreground(button, Role.ACCENT);
        button.setUI(new TextButtonUI() {
            @Override public void paint(Graphics graphics, JComponent c) {
                Graphics2D g = (Graphics2D) graphics.create(); quality(g);
                g.setColor(button.isEnabled() && button.getModel().isRollover() ? palette(button).hover() : palette(button).accentSoft());
                g.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), 16, 16); g.dispose(); super.paint(graphics, c);
            }
        });
        return button;
    }
    public static JToggleButton choice(String title) {
        JToggleButton button = new JToggleButton(title); button.setFont(FONT); button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(10, 14, 10, 14)); button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setUI(new BasicToggleButtonUI() {
            @Override protected void paintText(Graphics g, AbstractButton b, Rectangle bounds, String text) {
                buttonText(g, b, bounds, text);
            }
            @Override public void paint(Graphics graphics, JComponent c) {
                ThemePalette p = palette(button); Graphics2D g = (Graphics2D) graphics.create(); quality(g);
                g.setColor(button.isSelected() ? p.accentSoft() : button.isEnabled() && button.getModel().isRollover() ? p.hover() : p.soft());
                g.fillRoundRect(0, 0, button.getWidth(), button.getHeight(), 16, 16); g.dispose();
                button.setForeground(button.isSelected() ? BLUE : p.text()); super.paint(graphics, c);
            }
        });
        return button;
    }
    private static class TextButtonUI extends BasicButtonUI {
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle bounds, String text) {
            buttonText(g, b, bounds, text);
        }
    }
    // BasicButtonUI embosses disabled text with two offset draws. Use one theme-aware pass for every button family.
    private static void buttonText(Graphics graphics, AbstractButton button, Rectangle bounds, String text) {
        Graphics2D g = (Graphics2D) graphics.create(); quality(g); g.setFont(button.getFont());
        g.setColor(button.isEnabled() ? button.getForeground() : palette(button).muted());
        BasicGraphicsUtils.drawStringUnderlineCharAt(g, text, button.getDisplayedMnemonicIndex(),
                bounds.x, bounds.y + g.getFontMetrics().getAscent()); g.dispose();
    }
    public static JTextField field(String value, String name) {
        JTextField field = new JTextField(value) {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Object hint = getClientProperty("placeholder");
                if (getText().isEmpty() && hint instanceof String text) {
                    Graphics2D g = (Graphics2D) graphics.create(); quality(g); g.setColor(palette(this).muted()); g.setFont(getFont());
                    g.drawString(text, getInsets().left + 1, (getHeight() - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent()); g.dispose();
                }
            }
        };
        field.setName(name); field.setFont(FONT); field.setForeground(INK);
        field.setBackground(SOFT); field.setBorder(new EmptyBorder(10, 12, 10, 12));
        field.putClientProperty(FOREGROUND, Role.TEXT); field.putClientProperty(BACKGROUND, Role.SOFT);
        return field;
    }
    public static void scroll(JScrollPane pane, boolean transparent) {
        pane.setBorder(null); pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pane.setOpaque(!transparent); pane.getViewport().setOpaque(!transparent);
        if (!transparent) { surface(pane); surface(pane.getViewport()); }
        JScrollBar bar = pane.getVerticalScrollBar(); bar.setUnitIncrement(24); bar.setPreferredSize(new Dimension(7, 0));
        bar.setUI(new BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return zero(); }
            @Override protected JButton createIncreaseButton(int orientation) { return zero(); }
            private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension()); return b; }
            @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }
            @Override protected void paintThumb(Graphics graphics, JComponent c, Rectangle r) {
                Graphics2D g = (Graphics2D) graphics.create(); quality(g);
                Color base = palette(pane).muted();
                g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), isThumbRollover() ? 170 : 95));
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
            if (color.equals(Color.WHITE)) putClientProperty(BACKGROUND, Role.SURFACE);
            else if (color.equals(SOFT)) putClientProperty(BACKGROUND, Role.SOFT);
            else if (color.equals(OUTGOING)) putClientProperty(BACKGROUND, Role.OUTGOING);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); quality(g);
            if (shadow) { g.setColor(palette(this).shadow()); g.fillRoundRect(0, 2, getWidth(), getHeight() - 2, radius, radius); }
            g.setColor(getBackground()); g.fillRoundRect(0, 0, getWidth(), getHeight() - (shadow ? 2 : 0), radius, radius);
            g.dispose(); super.paintComponent(graphics);
        }
    }
}
