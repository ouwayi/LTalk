package cn.ltalk.client.ui;

import java.awt.Color;
import java.util.List;
import java.util.Objects;

/** Window-local appearance data. Light and dark backgrounds retain their own chosen colors. */
public record AppearanceSettings(Mode mode, BackgroundStyle style, Color lightStart, Color lightEnd,
                                 Color darkStart, Color darkEnd) {
    public enum Mode { LIGHT, DARK }
    public enum BackgroundStyle { SOLID, GRADIENT }
    public record Preset(String name, Color start, Color end) { }
    public AppearanceSettings {
        Objects.requireNonNull(mode); Objects.requireNonNull(style);
        for (Color color : List.of(lightStart, lightEnd, darkStart, darkEnd))
            if (color.getAlpha() != 255) throw new IllegalArgumentException("背景颜色必须为不透明颜色");
    }
    public static AppearanceSettings defaults() {
        return new AppearanceSettings(Mode.LIGHT, BackgroundStyle.GRADIENT,
                color("#EDF3FA"), color("#DCE7F3"), color("#111B27"), color("#213347"));
    }
    public boolean dark() { return mode == Mode.DARK; }
    public Color start() { return dark() ? darkStart : lightStart; }
    public Color end() { return dark() ? darkEnd : lightEnd; }
    public AppearanceSettings withMode(Mode value) { return new AppearanceSettings(value, style, lightStart, lightEnd, darkStart, darkEnd); }
    public AppearanceSettings withBackground(BackgroundStyle value, Color first, Color second) {
        return dark() ? new AppearanceSettings(mode, value, lightStart, lightEnd, first, second)
                : new AppearanceSettings(mode, value, first, second, darkStart, darkEnd);
    }
    public static Color color(String text) {
        if (text == null || !text.strip().matches("#[0-9A-Fa-f]{6}"))
            throw new IllegalArgumentException("颜色请使用 #RRGGBB 格式，例如 #DCE7F3");
        return new Color(Integer.parseInt(text.strip().substring(1), 16));
    }
    public static String hex(Color color) { return String.format(java.util.Locale.ROOT, "#%06X", color.getRGB() & 0xffffff); }
    public static List<Preset> presets(Mode mode) {
        return mode == Mode.LIGHT ? List.of(
                new Preset("雾蓝", color("#EDF3FA"), color("#DCE7F3")),
                new Preset("薄荷", color("#EFF7F4"), color("#D5EAE4")),
                new Preset("暖砂", color("#FAF5EE"), color("#EEE1D0")),
                new Preset("丁香", color("#F2F0FA"), color("#E1DDF0"))) : List.of(
                new Preset("夜蓝", color("#111B27"), color("#213347")),
                new Preset("松墨", color("#12221F"), color("#203C34")),
                new Preset("暖灰", color("#231F1C"), color("#3C332A")),
                new Preset("暮紫", color("#211B2E"), color("#35294B")));
    }
}
