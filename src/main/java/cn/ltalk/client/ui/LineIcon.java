package cn.ltalk.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;

/** Small vector icons stay sharp at desktop display scaling, without remote or font assets. */
public record LineIcon(Kind kind, Color color, int size) implements Icon {
    public enum Kind { MENU, COMPOSE, SEARCH, MORE, SEND, ATTACH, EMOJI, BACK, CLOSE, USERS, LOGOUT, CHECK }
    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }
    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create(); g.translate(x, y); g.scale(size / 24.0, size / 24.0);
        ChatTheme.quality(g); g.setColor(component.isEnabled() ? color : new Color(177, 189, 197));
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case MENU -> { line(g, 4, 6, 20, 6); line(g, 4, 12, 20, 12); line(g, 4, 18, 20, 18); }
            case SEARCH -> { g.draw(new Ellipse2D.Double(3, 3, 13, 13)); line(g, 14, 14, 21, 21); }
            case MORE -> { for (int n = 5; n <= 19; n += 7) g.fill(new Ellipse2D.Double(10.5, n - 1.5, 3, 3)); }
            case COMPOSE -> {
                Path2D p = new Path2D.Double(); p.moveTo(5, 15); p.lineTo(16, 4); p.lineTo(20, 8); p.lineTo(9, 19); p.lineTo(4, 20); p.closePath(); g.draw(p);
                line(g, 14, 6, 18, 10);
            }
            case SEND -> {
                Path2D p = new Path2D.Double(); p.moveTo(3, 3); p.lineTo(22, 12); p.lineTo(3, 21); p.lineTo(7, 12); p.closePath(); g.draw(p); line(g, 7, 12, 21, 12);
            }
            case ATTACH -> {
                g.rotate(Math.PI / 4, 12, 12);
                Path2D p = new Path2D.Double(); p.moveTo(17, 9); p.lineTo(17, 16); p.curveTo(17, 24, 5, 24, 5, 16);
                p.lineTo(5, 6); p.curveTo(5, 0, 14, 0, 14, 6); p.lineTo(14, 16); p.curveTo(14, 20, 9, 20, 9, 16); p.lineTo(9, 6); g.draw(p);
            }
            case EMOJI -> { g.drawOval(2, 2, 20, 20); g.fillOval(7, 7, 2, 2); g.fillOval(15, 7, 2, 2); g.drawArc(6, 8, 12, 10, 200, 140); }
            case BACK -> { line(g, 20, 12, 4, 12); line(g, 4, 12, 11, 5); line(g, 4, 12, 11, 19); }
            case CLOSE -> { line(g, 6, 6, 18, 18); line(g, 18, 6, 6, 18); }
            case CHECK -> { line(g, 4, 12, 10, 18); line(g, 10, 18, 21, 5); }
            case USERS -> {
                g.drawOval(8, 3, 8, 8); g.draw(new Arc2D.Double(4, 13, 16, 14, 0, 180, Arc2D.OPEN));
                g.draw(new Arc2D.Double(0, 5, 9, 7, 90, 180, Arc2D.OPEN)); g.draw(new Arc2D.Double(0, 14, 8, 10, 55, 125, Arc2D.OPEN));
            }
            case LOGOUT -> { line(g, 10, 4, 4, 4); line(g, 4, 4, 4, 20); line(g, 4, 20, 10, 20); line(g, 10, 12, 22, 12); line(g, 22, 12, 17, 7); line(g, 22, 12, 17, 17); }
        }
        g.dispose();
    }
    private static void line(Graphics2D g, double x1, double y1, double x2, double y2) { g.draw(new Line2D.Double(x1, y1, x2, y2)); }
}
