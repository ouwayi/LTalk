package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import static org.junit.jupiter.api.Assertions.*;

class AppearanceSettingsTest {
    @Test void rejectsInvalidOrTransparentColorsAndKeepsModesIndependent() {
        for (String invalid : new String[]{"", "red", "#123", "#12345678", "#ZZ1234", "123456"})
            assertThrows(IllegalArgumentException.class, () -> AppearanceSettings.color(invalid));
        assertEquals("#ABCDEF", AppearanceSettings.hex(AppearanceSettings.color(" #abcdef ")));
        var defaults = AppearanceSettings.defaults();
        assertThrows(IllegalArgumentException.class, () -> new AppearanceSettings(defaults.mode(), defaults.style(),
                new Color(0, 0, 0, 0), defaults.lightEnd(), defaults.darkStart(), defaults.darkEnd()));
        var custom = defaults.withBackground(defaults.style(), Color.ORANGE, Color.WHITE);
        assertEquals(defaults.darkStart(), custom.withMode(AppearanceSettings.Mode.DARK).start());
        assertEquals(Color.ORANGE, custom.withMode(AppearanceSettings.Mode.DARK).withMode(AppearanceSettings.Mode.LIGHT).start());
    }
    @Test void backgroundRendersUniformSolidAndChosenGradientWithNoTexture() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var mode : AppearanceSettings.Mode.values()) {
                var settings = AppearanceSettings.defaults().withMode(mode).withBackground(AppearanceSettings.BackgroundStyle.SOLID,
                        AppearanceSettings.color("#315579"), AppearanceSettings.color("#A0C0E0"));
                WallpaperPanel panel = new WallpaperPanel(null, settings); panel.setSize(160, 120);
                BufferedImage solid = paint(panel);
                for (int y = 0; y < 120; y += 3) for (int x = 0; x < 160; x += 3) assertEquals(settings.start().getRGB(), solid.getRGB(x, y));
                panel.setAppearance(settings.withBackground(AppearanceSettings.BackgroundStyle.GRADIENT, settings.start(), settings.end()));
                BufferedImage gradient = paint(panel); assertEquals(settings.start().getRGB(), gradient.getRGB(0, 0));
                Color last = new Color(gradient.getRGB(159, 119));
                assertTrue(Math.abs(last.getRed() - settings.end().getRed()) < 4 && Math.abs(last.getBlue() - settings.end().getBlue()) < 4);
                assertNotEquals(gradient.getRGB(10, 10), gradient.getRGB(140, 100));
            }
        });
    }
    private static BufferedImage paint(JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); component.paint(g); g.dispose(); return image;
    }
}
