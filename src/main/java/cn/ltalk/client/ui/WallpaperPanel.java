package cn.ltalk.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/** Original, low-contrast vector doodles inspired by a messenger wallpaper. No external image. */
public final class WallpaperPanel extends JPanel {
    private static final BufferedImage TILE = tile();
    public WallpaperPanel(LayoutManager layout) { super(layout); setOpaque(true); }
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
        g.setPaint(new GradientPaint(0, 0, new Color(215, 226, 175), getWidth(), getHeight(), new Color(160, 206, 174)));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setPaint(new TexturePaint(TILE, new Rectangle(0, 0, TILE.getWidth(), TILE.getHeight()))); g.fillRect(0, 0, getWidth(), getHeight());
        g.dispose();
    }
    private static BufferedImage tile() {
        BufferedImage tile = new BufferedImage(240, 220, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = tile.createGraphics(); ChatTheme.quality(g); g.setColor(new Color(76, 129, 87, 29));
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawRoundRect(20, 20, 34, 25, 12, 12); g.drawLine(29, 45, 25, 53); g.drawLine(25, 53, 39, 45);
        g.drawLine(29, 30, 44, 30); g.drawLine(29, 36, 38, 36);
        Path2D plane = new Path2D.Double(); plane.moveTo(144, 25); plane.lineTo(192, 12); plane.lineTo(177, 58);
        plane.lineTo(164, 43); plane.lineTo(154, 51); plane.lineTo(156, 37); plane.closePath(); g.draw(plane);
        g.drawLine(156, 37, 187, 17); g.drawLine(164, 43, 187, 17);
        g.drawOval(90, 65, 32, 32); g.drawArc(97, 73, 18, 17, 190, 150); g.drawOval(99, 74, 2, 2); g.drawOval(110, 74, 2, 2);
        for (int i = 0; i < 5; i++) {
            double angle = Math.PI * 2 * i / 5;
            g.drawOval((int) (36 + 12 * Math.cos(angle)), (int) (112 + 12 * Math.sin(angle)), 15, 15);
        }
        g.drawOval(39, 116, 10, 10);
        Path2D heart = new Path2D.Double(); heart.moveTo(172, 119); heart.curveTo(145, 98, 153, 84, 168, 97);
        heart.curveTo(181, 78, 202, 99, 172, 119); g.draw(heart);
        g.draw(new Arc2D.Double(83, 159, 48, 32, 5, 170, Arc2D.OPEN)); g.drawLine(83, 176, 133, 176);
        g.drawArc(98, 145, 10, 12, 100, 160); g.drawArc(113, 145, 10, 12, 100, 160);
        g.drawRoundRect(193, 161, 30, 35, 5, 5); g.drawLine(193, 171, 223, 171); g.drawLine(208, 161, 208, 196);
        for (Point p : new Point[]{new Point(78, 21), new Point(211, 83), new Point(18, 184), new Point(158, 183)}) {
            g.drawLine(p.x - 4, p.y, p.x + 4, p.y); g.drawLine(p.x, p.y - 4, p.x, p.y + 4);
        }
        g.dispose(); return tile;
    }
}
