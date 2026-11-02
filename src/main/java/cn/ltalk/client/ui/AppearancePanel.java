package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import java.awt.*;
import java.util.function.Consumer;

/** Editable draft with a live preview; only Apply publishes settings to the window. */
public final class AppearancePanel extends JPanel {
    private AppearanceSettings draft;
    private boolean syncing;
    private final JToggleButton light = ChatTheme.choice("浅色"), dark = ChatTheme.choice("深色");
    private final JToggleButton solid = ChatTheme.choice("纯色"), gradient = ChatTheme.choice("渐变");
    private final JTextField start = ChatTheme.field("", "backgroundStart"), end = ChatTheme.field("", "backgroundEnd");
    private final ColorField startColor = new ColorField(start, "起始色"), endColor = new ColorField(end, "结束色");
    private final JLabel error = ChatTheme.label(" ", 11, ChatTheme.RED, false);
    private final JButton apply = ChatTheme.primary("应用设置");
    private final JPanel presets = new JPanel(new GridLayout(1, 4, 6, 0));
    private final WallpaperPanel preview;

    public AppearancePanel(AppearanceSettings initial, Consumer<AppearanceSettings> onApply, Runnable onCancel) {
        super(new BorderLayout(0, 16)); draft = initial; ChatTheme.surface(this);
        setName("appearancePanel"); setBorder(new EmptyBorder(20, 22, 18, 22)); setPreferredSize(new Dimension(460, 512));

        light.setName("modeLight"); dark.setName("modeDark"); solid.setName("backgroundSolid"); gradient.setName("backgroundGradient");
        apply.setName("appearanceApply"); error.setName("appearanceError"); preview = new WallpaperPanel(new GridBagLayout(), initial); preview.setName("appearancePreview");
        ButtonGroup modes = new ButtonGroup(); modes.add(light); modes.add(dark);
        ButtonGroup styles = new ButtonGroup(); styles.add(solid); styles.add(gradient);
        JPanel form = new JPanel(new GridBagLayout()); form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        section(form, c, "显示模式", row(light, dark)); section(form, c, "聊天背景", row(solid, gradient));
        presets.setOpaque(false); section(form, c, "预设配色", presets);
        JPanel fields = new JPanel(new GridLayout(1, 2, 12, 0)); fields.setOpaque(false);
        fields.add(labeled("背景颜色 / 起始色", startColor)); fields.add(labeled("渐变结束色", endColor));
        c.gridy++; c.insets = new Insets(6, 0, 0, 0); form.add(fields, c);
        c.gridy++; c.insets = new Insets(4, 0, 6, 0); form.add(error, c);
        preview.setPreferredSize(new Dimension(380, 112));
        GridBagConstraints bubble = new GridBagConstraints(); bubble.insets = new Insets(8, 15, 5, 15); bubble.gridx = 0; bubble.gridy = 0; bubble.weightx = 1; bubble.anchor = GridBagConstraints.WEST;
        ChatTheme.RoundedPanel incoming = new ChatTheme.RoundedPanel(new BorderLayout(), 16, Color.WHITE, true);
        incoming.setBorder(new EmptyBorder(8, 12, 8, 12)); incoming.add(ChatTheme.label("消息预览", 12, ChatTheme.INK, false)); preview.add(incoming, bubble);
        ChatTheme.RoundedPanel outgoing = new ChatTheme.RoundedPanel(new BorderLayout(), 16, ChatTheme.OUTGOING, true);
        outgoing.setBorder(new EmptyBorder(8, 12, 8, 12)); outgoing.add(ChatTheme.label("这样的配色很舒服", 12, ChatTheme.INK, false));
        bubble.gridy = 1; bubble.anchor = GridBagConstraints.EAST; preview.add(outgoing, bubble);
        c.gridy++; c.insets = new Insets(0, 0, 0, 0); c.weighty = 1; c.fill = GridBagConstraints.BOTH; form.add(preview, c); add(form);

        JPanel footer = new JPanel(new BorderLayout(8, 0)); footer.setOpaque(false);
        JButton reset = ChatTheme.pill("恢复默认"); reset.setName("appearanceReset"); footer.add(reset, BorderLayout.WEST);
        JButton cancel = ChatTheme.pill("取消"); cancel.setName("appearanceCancel");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); actions.setOpaque(false); actions.add(cancel); actions.add(apply); footer.add(actions);
        add(footer, BorderLayout.SOUTH);
        light.addActionListener(e -> changeMode(AppearanceSettings.Mode.LIGHT)); dark.addActionListener(e -> changeMode(AppearanceSettings.Mode.DARK));
        solid.addActionListener(e -> changeStyle(AppearanceSettings.BackgroundStyle.SOLID)); gradient.addActionListener(e -> changeStyle(AppearanceSettings.BackgroundStyle.GRADIENT));
        DocumentListener colorsChanged = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { readColors(); }
            @Override public void removeUpdate(DocumentEvent e) { readColors(); }
            @Override public void changedUpdate(DocumentEvent e) { readColors(); }
        };
        start.getDocument().addDocumentListener(colorsChanged); end.getDocument().addDocumentListener(colorsChanged);
        apply.addActionListener(e -> { if (readColors()) onApply.accept(draft); }); cancel.addActionListener(e -> onCancel.run());
        reset.addActionListener(e -> { draft = AppearanceSettings.defaults(); syncForm(); }); syncForm();
    }
    private static JPanel row(Component... components) {
        JPanel panel = new JPanel(new GridLayout(1, components.length, 8, 0)); panel.setOpaque(false);
        for (Component component : components) panel.add(component); return panel;
    }
    private static JPanel labeled(String title, JComponent component) {
        JPanel panel = new JPanel(new BorderLayout(0, 5)); panel.setOpaque(false);
        panel.add(ChatTheme.label(title, 11, ChatTheme.MUTED, false), BorderLayout.NORTH); panel.add(component); return panel;
    }
    private static void section(JPanel form, GridBagConstraints c, String title, Component content) {
        c.gridy++; c.insets = new Insets(0, 0, 6, 0); form.add(ChatTheme.label(title, 12, ChatTheme.INK, true), c);
        c.gridy++; c.insets = new Insets(0, 0, 12, 0); form.add(content, c);
    }
    private void changeMode(AppearanceSettings.Mode mode) { if (!syncing) { draft = draft.withMode(mode); syncForm(); } }
    private void changeStyle(AppearanceSettings.BackgroundStyle style) {
        if (!syncing) { draft = draft.withBackground(style, draft.start(), draft.end()); syncForm(); }
    }
    private void syncForm() {
        syncing = true;
        try {
            startColor.closePicker(); endColor.closePicker();
            light.setSelected(!draft.dark()); dark.setSelected(draft.dark());
            solid.setSelected(draft.style() == AppearanceSettings.BackgroundStyle.SOLID); gradient.setSelected(!solid.isSelected());
            start.setText(AppearanceSettings.hex(draft.start())); end.setText(AppearanceSettings.hex(draft.end())); endColor.setEnabled(!solid.isSelected());
            presets.removeAll();
            for (AppearanceSettings.Preset preset : AppearanceSettings.presets(draft.mode())) {
                JButton swatch = ChatTheme.pill(preset.name()); swatch.setName("preset" + preset.name());
                swatch.setBorder(new EmptyBorder(8, 3, 8, 3));
                swatch.setIcon(new Icon() {
                    @Override public int getIconWidth() { return 18; }
                    @Override public int getIconHeight() { return 18; }
                    @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
                        Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
                        g.setPaint(new GradientPaint(x, y, preset.start(), x + 18, y + 18, preset.end())); g.fillOval(x, y, 18, 18); g.dispose();
                    }
                });
                swatch.addActionListener(e -> { draft = draft.withBackground(draft.style(), preset.start(), preset.end()); syncForm(); }); presets.add(swatch);
            }
            error.setText(" "); apply.setEnabled(true); refreshPreview();
        } finally { syncing = false; }
    }
    private boolean readColors() {
        if (syncing) return true;
        try {
            Color first = AppearanceSettings.color(start.getText());
            Color second = draft.style() == AppearanceSettings.BackgroundStyle.SOLID ? draft.end() : AppearanceSettings.color(end.getText());
            draft = draft.withBackground(draft.style(), first, second); error.setText(" "); apply.setEnabled(true); refreshPreview(); return true;
        } catch (IllegalArgumentException ex) { error.setText(ex.getMessage()); apply.setEnabled(false); return false; }
    }
    private void refreshPreview() {
        preview.setAppearance(draft); ChatTheme.apply(this, ThemePalette.of(draft)); revalidate(); repaint();
    }
}
