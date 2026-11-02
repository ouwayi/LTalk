package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;

/** A hex field with a compact, themed picker. Invalid manual input remains available for correction. */
public final class ColorField extends JPanel {
    private final JTextField text;
    private final JButton swatch = ChatTheme.pill("");
    private final JPopupMenu popup;
    private Color selected = Color.WHITE;

    public ColorField(JTextField text, String title) {
        super(new BorderLayout(5, 0)); setOpaque(false); this.text = text;
        try { selected = AppearanceSettings.color(text.getText()); } catch (IllegalArgumentException ignored) { }
        text.setColumns(7); swatch.setName(text.getName() + "Picker");
        swatch.setPreferredSize(new Dimension(36, 36)); swatch.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        swatch.setToolTipText("选择" + title); swatch.getAccessibleContext().setAccessibleName("选择" + title);
        swatch.setIcon(new Icon() {
            public int getIconWidth() { return 22; }
            public int getIconHeight() { return 22; }
            public void paintIcon(Component c, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
                g.setColor(selected); g.fillRoundRect(x, y, 21, 21, 6, 6);
                g.setColor(ChatTheme.palette(c).muted()); g.drawRoundRect(x, y, 21, 21, 6, 6); g.dispose();
            }
        });
        ColorPicker picker = new ColorPicker(selected, color -> text.setText(AppearanceSettings.hex(color)));
        popup = new JPopupMenu() {
            @Override public void show(Component invoker, int x, int y) {
                if (!swatch.isEnabled()) return;
                picker.setColor(selected); ChatTheme.apply(this, ChatTheme.palette(ColorField.this));
                setBorder(BorderFactory.createLineBorder(ChatTheme.palette(ColorField.this).line()));
                super.show(invoker, x, y); picker.focusSelection();
            }
        };
        popup.add(picker); swatch.setComponentPopupMenu(popup);
        swatch.addActionListener(e -> popup.show(swatch, 0, swatch.getHeight()));
        text.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
            private void update() {
                try { selected = AppearanceSettings.color(text.getText()); swatch.repaint(); }
                catch (IllegalArgumentException ignored) { /* Keep the last valid swatch until input is complete. */ }
            }
        });
        add(text); add(swatch, BorderLayout.EAST);
    }
    public void closePicker() { popup.setVisible(false); }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (text != null) { text.setEnabled(enabled); swatch.setEnabled(enabled); if (!enabled) closePicker(); }
    }
    @Override public void removeNotify() { closePicker(); super.removeNotify(); }
}
