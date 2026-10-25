package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;

/** Styled glyph fallback, but always a plain text document: no HTML, URLs, scripts or attachments. */
final class MessageText extends JTextPane {
    private final Font normal = ChatTheme.FONT.deriveFont(15f);
    private final Font emoji = new Font("Segoe UI Emoji", Font.PLAIN, 15);
    private final Font logical = new Font(Font.DIALOG, Font.PLAIN, 15);
    MessageText(String text) {
        setEditorKit(new StyledEditorKit() {
            private final ViewFactory factory = element -> switch (element.getName()) {
                case AbstractDocument.ContentElementName -> new LabelView(element) {
                    @Override public float getMinimumSpan(int axis) { return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis); }
                };
                case AbstractDocument.ParagraphElementName -> new ParagraphView(element);
                case AbstractDocument.SectionElementName -> new BoxView(element, View.Y_AXIS);
                case StyleConstants.ComponentElementName -> new ComponentView(element);
                case StyleConstants.IconElementName -> new IconView(element);
                default -> new LabelView(element);
            };
            @Override public ViewFactory getViewFactory() { return factory; }
        });
        setEditable(false); setOpaque(false); setBorder(null); setMargin(new Insets(0, 0, 0, 0));
        setFont(normal); setForeground(ChatTheme.INK); setName("messageText");
        SimpleAttributeSet base = new SimpleAttributeSet();
        StyleConstants.setFontFamily(base, normal.getFamily()); StyleConstants.setFontSize(base, 15);
        StyleConstants.setForeground(base, ChatTheme.INK);
        try {
            getStyledDocument().insertString(0, text, base);
            for (int offset = 0; offset < text.length();) {
                int codePoint = text.codePointAt(offset), length = Character.charCount(codePoint);
                if (!normal.canDisplay(codePoint)) {
                    SimpleAttributeSet fallback = new SimpleAttributeSet(base);
                    StyleConstants.setFontFamily(fallback, emoji.canDisplay(codePoint) ? emoji.getFamily() : logical.getFamily());
                    getStyledDocument().setCharacterAttributes(offset, length, fallback, false);
                }
                offset += length;
            }
        } catch (BadLocationException ex) { throw new IllegalStateException(ex); }
    }
    int naturalWidth() {
        int maximum = 0, line = 0;
        String text = getText();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (codePoint == '\n') { maximum = Math.max(maximum, line); line = 0; }
            else {
                Font font = normal.canDisplay(codePoint) ? normal : emoji.canDisplay(codePoint) ? emoji : logical;
                line += getFontMetrics(font).charWidth(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        return Math.max(maximum, line);
    }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
}
