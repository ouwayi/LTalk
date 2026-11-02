package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

/** Opaque HSV selection. Mouse dragging and keyboard arrows share the same bounded color model. */
public final class ColorPicker extends JPanel {
    private float hue, saturation, brightness;
    private boolean syncing;
    private final Consumer<Color> changed;
    private final Spectrum spectrum = new Spectrum();
    private final JSlider hueSlider = new JSlider(0, 359);
    private final JLabel value = ChatTheme.label(" ", 12, ChatTheme.INK, false);

    public ColorPicker(Color initial, Consumer<Color> changed) {
        super(new BorderLayout(0, 9)); this.changed = changed; ChatTheme.surface(this);
        setName("colorPicker"); setBorder(new EmptyBorder(12, 12, 12, 12));
        add(ChatTheme.label("拖动选色 · 方向键微调", 12, ChatTheme.MUTED, false), BorderLayout.NORTH);
        add(spectrum);
        JPanel controls = new JPanel(new BorderLayout(0, 5)); controls.setOpaque(false);
        hueSlider.setName("colorHue"); hueSlider.setOpaque(false);
        hueSlider.getAccessibleContext().setAccessibleName("色相");
        hueSlider.setToolTipText("拖动调节色相，也可使用左右方向键");
        hueSlider.setUI(new BasicSliderUI(hueSlider) {
            @Override public void paintTrack(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
                int y = trackRect.y + (trackRect.height - 10) / 2;
                for (int x = 0; x < trackRect.width; x++) {
                    g.setColor(Color.getHSBColor(x / (float) Math.max(1, trackRect.width - 1), 1, 1));
                    g.drawLine(trackRect.x + x, y, trackRect.x + x, y + 10);
                }
                g.dispose();
            }
            @Override public void paintThumb(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
                g.setColor(Color.WHITE); g.fillOval(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height);
                g.setColor(Color.DARK_GRAY); g.drawOval(thumbRect.x, thumbRect.y, thumbRect.width - 1, thumbRect.height - 1); g.dispose();
            }
            @Override public void paintFocus(Graphics g) {
                g.setColor(ChatTheme.BLUE); g.drawRect(0, 0, hueSlider.getWidth() - 1, hueSlider.getHeight() - 1);
            }
        });
        hueSlider.addChangeListener(e -> {
            if (!syncing) { hue = hueSlider.getValue() / 360f; publish(); }
        });
        controls.add(hueSlider); controls.add(value, BorderLayout.SOUTH); add(controls, BorderLayout.SOUTH);
        setColor(initial);
    }
    public Color color() { return Color.getHSBColor(hue, saturation, brightness); }
    public void focusSelection() { spectrum.requestFocusInWindow(); }
    public void setColor(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        // Keep the chosen hue at white/gray/black so changing brightness can recover it.
        if (hsb[1] > 0) hue = hsb[0];
        if (hsb[2] > 0) saturation = hsb[1];
        brightness = hsb[2]; syncing = true;
        try { hueSlider.setValue(Math.round(hue * 360) % 360); } finally { syncing = false; }
        refresh();
    }
    private void publish() { refresh(); changed.accept(color()); }
    private void refresh() {
        value.setText(AppearanceSettings.hex(color()));
        spectrum.getAccessibleContext().setAccessibleDescription("当前颜色 " + value.getText() + "；左右调饱和度，上下调亮度");
        spectrum.repaint();
    }
    private final class Spectrum extends JPanel {
        Spectrum() {
            setName("colorSpectrum"); setPreferredSize(new Dimension(240, 150)); setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            getAccessibleContext().setAccessibleName("饱和度与亮度色盘");
            setToolTipText("拖动选色；左右调饱和度，上下调亮度");
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) { requestFocusInWindow(); select(e); }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if ((e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) select(e);
                }
                private void select(MouseEvent e) {
                    saturation = clamp(e.getX() / (float) Math.max(1, getWidth() - 1));
                    brightness = 1 - clamp(e.getY() / (float) Math.max(1, getHeight() - 1)); publish();
                }
            };
            addMouseListener(mouse); addMouseMotionListener(mouse);
            bind("LEFT", -.01f, 0); bind("RIGHT", .01f, 0); bind("UP", 0, .01f); bind("DOWN", 0, -.01f);
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
        }
        private void bind(String key, float ds, float db) {
            getInputMap().put(KeyStroke.getKeyStroke(key), key);
            getActionMap().put(key, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    saturation = clamp(saturation + ds); brightness = clamp(brightness + db); publish();
                }
            });
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
            int w = Math.max(1, getWidth() - 1), h = Math.max(1, getHeight() - 1);
            g.setPaint(new GradientPaint(0, 0, Color.WHITE, w, 0, Color.getHSBColor(hue, 1, 1)));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 0), 0, h, Color.BLACK));
            g.fillRect(0, 0, getWidth(), getHeight());
            int x = Math.round(saturation * w), y = Math.round((1 - brightness) * h);
            g.setStroke(new BasicStroke(3)); g.setColor(Color.BLACK); g.drawOval(x - 5, y - 5, 10, 10);
            g.setStroke(new BasicStroke(1)); g.setColor(Color.WHITE); g.drawOval(x - 5, y - 5, 10, 10);
            g.setColor(hasFocus() ? ChatTheme.BLUE : ChatTheme.palette(this).line()); g.drawRect(0, 0, w, h); g.dispose();
        }
    }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
}
