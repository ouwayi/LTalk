package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ColorPickerTest {
    @Test void dragHueAndSpectrumClampsAtEdgesAndKeyboardCanLeaveBlack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<Color> chosen = new AtomicReference<>();
            ColorPicker picker = new ColorPicker(Color.RED, chosen::set);
            JPanel square = (JPanel) find(picker, "colorSpectrum"); square.setSize(201, 101);
            JSlider hue = (JSlider) find(picker, "colorHue");
            hue.setValue(120); assertEquals(Color.GREEN, chosen.get());
            mouse(square, MouseEvent.MOUSE_PRESSED, 100, 50);
            Color mid = chosen.get(); assertTrue(mid.getGreen() >= 127 && mid.getGreen() <= 128);
            assertTrue(mid.getRed() >= 63 && mid.getRed() <= 64); assertEquals(mid.getRed(), mid.getBlue());
            mouse(square, MouseEvent.MOUSE_DRAGGED, 500, -20); assertEquals(Color.GREEN, chosen.get());
            mouse(square, MouseEvent.MOUSE_DRAGGED, -20, -20); assertEquals(Color.WHITE, chosen.get());
            mouse(square, MouseEvent.MOUSE_DRAGGED, 200, 300); assertEquals(Color.BLACK, chosen.get());
            square.getActionMap().get("UP").actionPerformed(new ActionEvent(square, 0, "UP"));
            assertTrue(chosen.get().getGreen() > 0); assertEquals(0, chosen.get().getRed());
            picker.setColor(Color.BLUE); assertEquals(Color.BLUE, picker.color());
            picker.setColor(Color.BLACK); hue.setValue(0);
            square.getActionMap().get("UP").actionPerformed(new ActionEvent(square, 0, "UP"));
            assertTrue(chosen.get().getRed() > 0); assertEquals(0, chosen.get().getBlue());
        });
    }
    @Test void pickerChangesDraftOnlyAndBothColorsRemainIndependentAcrossModes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<AppearanceSettings> applied = new AtomicReference<>(); AtomicBoolean canceled = new AtomicBoolean();
            AppearancePanel panel = new AppearancePanel(AppearanceSettings.defaults(), applied::set, () -> canceled.set(true));
            JTextField start = (JTextField) find(panel, "backgroundStart"), end = (JTextField) find(panel, "backgroundEnd");
            ColorPicker first = picker(panel, "backgroundStartPicker"), second = picker(panel, "backgroundEndPicker");
            start.setText("#bad"); assertFalse(find(panel, "appearanceApply").isEnabled());
            ((JSlider) find(first, "colorHue")).setValue(240);
            JPanel spectrum = (JPanel) find(first, "colorSpectrum"); spectrum.setSize(201, 101);
            mouse(spectrum, MouseEvent.MOUSE_PRESSED, 200, 0);
            assertEquals("#0000FF", start.getText()); assertTrue(find(panel, "appearanceApply").isEnabled());
            String lightEnd = end.getText();
            ((JToggleButton) find(panel, "modeDark")).doClick();
            assertEquals("#101010", start.getText());
            ((JSlider) find(second, "colorHue")).setValue(120);
            JPanel endSpectrum = (JPanel) find(second, "colorSpectrum"); endSpectrum.setSize(201, 101);
            mouse(endSpectrum, MouseEvent.MOUSE_PRESSED, 200, 0); assertEquals("#00FF00", end.getText());
            ((JToggleButton) find(panel, "backgroundSolid")).doClick();
            assertFalse(find(panel, "backgroundEndPicker").isEnabled()); assertFalse(end.isEnabled());
            ((JToggleButton) find(panel, "backgroundGradient")).doClick();
            assertTrue(find(panel, "backgroundEndPicker").isEnabled()); assertEquals("#00FF00", end.getText());
            ((JToggleButton) find(panel, "modeLight")).doClick();
            assertEquals("#0000FF", start.getText()); assertEquals(lightEnd, end.getText()); assertNull(applied.get());
            ((JButton) find(panel, "appearanceApply")).doClick(); assertEquals(Color.BLUE, applied.get().start());
            start.setText("#123456"); ((JButton) find(panel, "appearanceCancel")).doClick();
            assertTrue(canceled.get()); assertEquals(Color.BLUE, applied.get().start());
        });
    }
    private static ColorPicker picker(Container panel, String name) {
        return (ColorPicker) ((JButton) find(panel, name)).getComponentPopupMenu().getComponent(0);
    }
    @Test void popupUsesEditedHexAndCurrentThemeAndClosesOnModeChangeOrDisposal() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame window = new JFrame("Color picker component test");
            try {
                AppearancePanel panel = new AppearancePanel(AppearanceSettings.defaults(), value -> { }, () -> { });
                window.setContentPane(panel); window.pack(); window.setVisible(true);
                JButton start = (JButton) find(panel, "backgroundStartPicker"), end = (JButton) find(panel, "backgroundEndPicker");
                ((JTextField) find(panel, "backgroundStart")).setText("#123456"); start.doClick();
                JPopupMenu popup = start.getComponentPopupMenu(); assertTrue(popup.isVisible());
                assertEquals(AppearanceSettings.color("#123456"), picker(panel, "backgroundStartPicker").color());
                ((JToggleButton) find(panel, "modeDark")).doClick(); assertFalse(popup.isVisible());
                // Context activation must go through the same initialization as a normal click.
                popup.show(start, 0, start.getHeight()); assertTrue(ChatTheme.palette(popup).dark());
                assertEquals(AppearanceSettings.color("#101010"), picker(panel, "backgroundStartPicker").color());
                popup.setVisible(false);
                ((JToggleButton) find(panel, "backgroundSolid")).doClick();
                end.getComponentPopupMenu().show(end, 0, end.getHeight()); assertFalse(end.getComponentPopupMenu().isVisible());
                start.doClick(); assertTrue(popup.isVisible()); window.dispose(); assertFalse(popup.isVisible());
            } finally { window.dispose(); }
        });
    }
    private static void mouse(JComponent component, int type, int x, int y) {
        component.dispatchEvent(new MouseEvent(component, type, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK,
                x, y, 1, false, MouseEvent.BUTTON1));
    }
    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) { Component found = find(container, name); if (found != null) return found; }
        }
        return null;
    }
}
