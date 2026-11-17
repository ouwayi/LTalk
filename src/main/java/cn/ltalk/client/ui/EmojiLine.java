package cn.ltalk.client.ui;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.font.GraphicAttribute;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.font.TextMeasurer;
import java.text.AttributedString;
import java.util.HashMap;
import java.util.Map;

/** Lays out a one-line conversation label with the same emoji artwork as the composer. */
final class EmojiLine {
    private EmojiLine() { }

    static void draw(Component component, Graphics2D graphics, String text, int x, int baseline, int width) {
        if (width <= 0) return;
        String plain = text.replaceAll("[\\r\\n\\t]+", " ");
        var glyphs = EmojiIcons.GRAPHEME.matcher(plain);
        StringBuilder display = new StringBuilder(plain.length());
        Map<Integer, GraphicAttribute> icons = new HashMap<>();
        int size = graphics.getFont().getSize() + 2;
        while (glyphs.find()) {
            String value = glyphs.group();
            Icon emoji = EmojiIcons.icon(value, size);
            if (emoji == null) display.append(value);
            else {
                icons.put(display.length(), new InlineIcon(component, emoji));
                display.append('\uFFFC');
            }
        }
        if (icons.isEmpty()) {
            graphics.drawString(ConversationList.ellipsis(graphics.getFontMetrics(), plain, width), x, baseline);
            return;
        }
        var styled = new AttributedString(display.toString());
        styled.addAttribute(TextAttribute.FONT, graphics.getFont());
        icons.forEach((offset, icon) -> styled.addAttribute(TextAttribute.CHAR_REPLACEMENT, icon, offset, offset + 1));
        var metrics = new TextMeasurer(styled.getIterator(), graphics.getFontRenderContext());
        int end = display.length();
        if (metrics.getAdvanceBetween(0, end) <= width) {
            metrics.getLayout(0, end).draw(graphics, x, baseline);
            return;
        }
        var ellipsis = new TextLayout("…", graphics.getFont(), graphics.getFontRenderContext());
        float available = width - ellipsis.getAdvance();
        if (available < 0) return;
        int fit = metrics.getLineBreakIndex(0, available);
        var boundaries = EmojiIcons.GRAPHEME.matcher(display);
        end = 0;
        while (boundaries.find() && boundaries.end() <= fit) end = boundaries.end();
        float ellipsisX = x;
        if (end > 0) {
            var visible = metrics.getLayout(0, end);
            visible.draw(graphics, x, baseline);
            ellipsisX += visible.getAdvance();
        }
        ellipsis.draw(graphics, ellipsisX, baseline);
    }

    private static final class InlineIcon extends GraphicAttribute {
        private final Component component;
        private final Icon icon;
        InlineIcon(Component component, Icon icon) {
            super(ROMAN_BASELINE);
            this.component = component;
            this.icon = icon;
        }
        @Override public float getAscent() { return Math.round(icon.getIconHeight() * .82f); }
        @Override public float getDescent() { return icon.getIconHeight() - getAscent(); }
        @Override public float getAdvance() { return icon.getIconWidth(); }
        @Override public void draw(Graphics2D graphics, float x, float y) {
            icon.paintIcon(component, graphics, Math.round(x), Math.round(y - getAscent()));
        }
    }
}
