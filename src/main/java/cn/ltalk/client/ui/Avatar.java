package cn.ltalk.client.ui;

import javax.swing.*;
import java.awt.*;

public record Avatar(String name, boolean group, int size) implements Icon {
    private static final Color[] COLORS = {new Color(92, 178, 234), new Color(166, 131, 226),
            new Color(237, 164, 98), new Color(99, 190, 166), new Color(218, 128, 156), new Color(118, 160, 219)};
    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }
    @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
        int hash = name.hashCode() ^ (name.hashCode() >>> 16);
        Color color = group ? ChatTheme.BLUE : COLORS[Math.floorMod(hash, COLORS.length)];
        g.setPaint(new GradientPaint(x, y, color.brighter(), x, y + size, color)); g.fillOval(x, y, size, size);
        if (group) new LineIcon(LineIcon.Kind.USERS, Color.WHITE, size / 2).paintIcon(c, g, x + size / 4, y + size / 4);
        else {
            String initial = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.ROOT);
            g.setColor(Color.WHITE); g.setFont(ChatTheme.FONT.deriveFont(Font.BOLD, size * .41f));
            FontMetrics fm = g.getFontMetrics(); g.drawString(initial, x + (size - fm.stringWidth(initial)) / 2, y + (size - fm.getHeight()) / 2 + fm.getAscent());
        }
        g.dispose();
    }
}
