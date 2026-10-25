package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AppearancePanelTest {
    @Test void editPreviewRejectsBadHexAndAppliesValidDarkSolidSettings() throws Exception {
        AtomicReference<AppearanceSettings> saved = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AppearancePanel panel = new AppearancePanel(AppearanceSettings.defaults(), saved::set, () -> { });
            ((JToggleButton) find(panel, "modeDark")).doClick(); assertTrue(ChatTheme.palette(panel).dark());
            JTextField start = (JTextField) find(panel, "backgroundStart"); JButton apply = (JButton) find(panel, "appearanceApply");
            start.setText("#bad"); assertFalse(apply.isEnabled()); assertTrue(((JLabel) find(panel, "appearanceError")).getText().contains("#RRGGBB"));
            start.setText("#253649"); ((JToggleButton) find(panel, "backgroundSolid")).doClick();
            assertFalse(find(panel, "backgroundEnd").isEnabled()); assertTrue(apply.isEnabled()); assertNull(saved.get());
            render(panel);
            apply.doClick(); assertTrue(saved.get().dark()); assertEquals(AppearanceSettings.BackgroundStyle.SOLID, saved.get().style());
            assertEquals(AppearanceSettings.color("#253649"), saved.get().start());
        });
    }
    private static void render(JPanel panel) {
        try {
            panel.setSize(panel.getPreferredSize()); layout(panel);
            BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); panel.paint(g); g.dispose();
            Path path = Path.of("target/ui-preview/appearance-settings.png"); Files.createDirectories(path.getParent()); ImageIO.write(image, "png", path.toFile());
        } catch (Exception ex) { throw new AssertionError(ex); }
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component c : parent.getComponents()) if (c instanceof Container nested) layout(nested); }
    @Test void cancelDoesNotPublishAndResetRestoresDraftDefaults() throws Exception {
        AtomicInteger saves = new AtomicInteger(); AtomicBoolean canceled = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            AppearancePanel panel = new AppearancePanel(AppearanceSettings.defaults(), value -> saves.incrementAndGet(), () -> canceled.set(true));
            ((JToggleButton) find(panel, "modeDark")).doClick(); ((JButton) find(panel, "appearanceReset")).doClick();
            assertFalse(ChatTheme.palette(panel).dark()); assertEquals("#EDF3FA", ((JTextField) find(panel, "backgroundStart")).getText());
            ((JButton) find(panel, "appearanceCancel")).doClick(); assertTrue(canceled.get()); assertEquals(0, saves.get());
        });
    }
    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) { Component found = find(container, name); if (found != null) return found; }
        }
        return null;
    }
}
