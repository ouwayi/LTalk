package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatPopupMenuTest {
    @Test void themedRowsKeepActionsDisabledBehaviorAndHoverFeedback() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var mode : AppearanceSettings.Mode.values()) {
                JPanel owner = new JPanel(); ThemePalette palette = ThemePalette.of(AppearanceSettings.defaults().withMode(mode)); ChatTheme.apply(owner, palette);
                ChatPopupMenu menu = new ChatPopupMenu(owner, "LTalk"); AtomicInteger actions = new AtomicInteger();
                JMenuItem active = menu.action("appearance", "外观设置", LineIcon.Kind.PALETTE, actions::incrementAndGet);
                JMenuItem disabled = menu.action("disconnect", "断开连接", LineIcon.Kind.LOGOUT, actions::incrementAndGet); disabled.setEnabled(false);
                active.doClick(); disabled.doClick(); assertEquals(1, actions.get()); assertEquals(42, active.getPreferredSize().height);
                active.setSize(active.getPreferredSize()); active.getModel().setArmed(true);
                BufferedImage row = paint(active); assertEquals(palette.hover().getRGB(), row.getRGB(3, 10));
                menu.setSize(menu.getPreferredSize()); menu.doLayout();
                try { Path directory = Path.of("target/ui-preview"); Files.createDirectories(directory); ImageIO.write(paint(menu), "png", directory.resolve(mode == AppearanceSettings.Mode.DARK ? "menu-dark.png" : "menu-light.png").toFile()); }
                catch (Exception ex) { throw new AssertionError(ex); }
            }
        });
    }
    @Test void windowsKeepIndependentPalettes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel light = new JPanel(), dark = new JPanel();
            ThemePalette daylight = ThemePalette.of(AppearanceSettings.defaults());
            ThemePalette night = ThemePalette.of(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK));
            ChatTheme.apply(light, daylight); ChatTheme.apply(dark, night);
            ChatPopupMenu first = new ChatPopupMenu(light, "菜单"), second = new ChatPopupMenu(dark, "菜单");
            assertEquals(daylight, ChatTheme.palette(first)); assertEquals(night, ChatTheme.palette(second)); assertFalse(ChatTheme.palette(light).dark());
        });
    }
    private static BufferedImage paint(JComponent component) {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); component.paint(g); g.dispose(); return image;
    }
}
