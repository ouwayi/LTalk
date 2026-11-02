package cn.ltalk.client.ui;

import java.awt.Color;

/** Semantic colors for all surfaces, including popups and messages. */
public record ThemePalette(boolean dark, Color surface, Color soft, Color text, Color muted,
                           Color line, Color hover, Color outgoing, Color disabled, Color danger,
                           Color errorSurface, Color accentSoft) {
    public enum Role { SURFACE, SOFT, TEXT, MUTED, LINE, HOVER, OUTGOING, DISABLED, DANGER, ERROR_SURFACE, ACCENT, ACCENT_SOFT }
    public static ThemePalette of(AppearanceSettings settings) {
        return settings.dark() ? new ThemePalette(true,
                rgb(0x181818), rgb(0x242424), rgb(0xF0F0F0), rgb(0xB0B0B0), rgb(0x3A3A3A), rgb(0x303030),
                rgb(0x323232), rgb(0x383838), rgb(0xFF9298), rgb(0x45282B), rgb(0x223346))
                : new ThemePalette(false, Color.WHITE, ChatTheme.SOFT, ChatTheme.INK, ChatTheme.MUTED, ChatTheme.LINE,
                rgb(0xEDF3F9), rgb(0xE4F0FC), rgb(0xC4D4E3), rgb(0xC94752), rgb(0xFFECEE), rgb(0xE9F4FF));
    }
    private static Color rgb(int value) { return new Color(value); }
    public Color color(Role role) {
        return switch (role) {
            case SURFACE -> surface; case SOFT -> soft; case TEXT -> text; case MUTED -> muted;
            case LINE -> line; case HOVER -> hover; case OUTGOING -> outgoing; case DISABLED -> disabled;
            case DANGER -> danger; case ERROR_SURFACE -> errorSurface; case ACCENT -> ChatTheme.BLUE; case ACCENT_SOFT -> accentSoft;
        };
    }
    public Color shadow() { return new Color(0, 0, 0, dark ? 75 : 20); }
}
