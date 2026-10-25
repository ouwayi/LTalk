package cn.ltalk.client.ui;

import javax.swing.*;
import java.awt.*;

/** A configurable solid or gradient chat canvas, without patterns or external assets. */
public final class WallpaperPanel extends JPanel {
    private AppearanceSettings appearance;
    public WallpaperPanel(LayoutManager layout) { this(layout, AppearanceSettings.defaults()); }
    public WallpaperPanel(LayoutManager layout, AppearanceSettings appearance) {
        super(layout); this.appearance = appearance; setOpaque(true);
    }
    public void setAppearance(AppearanceSettings value) { appearance = value; repaint(); }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
        if (appearance.style() == AppearanceSettings.BackgroundStyle.SOLID) g.setColor(appearance.start());
        else g.setPaint(new GradientPaint(0, 0, appearance.start(), Math.max(1, getWidth()), Math.max(1, getHeight()), appearance.end()));
        g.fillRect(0, 0, getWidth(), getHeight()); g.dispose();
    }
}
