package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;

/** Keeps Unicode in the document while representing supported graphemes with icons. */
class EmojiText extends JTextPane {
    private static final Font NORMAL = ChatTheme.FONT.deriveFont(15f);
    private static final Font EMOJI_FONT = new Font("Segoe UI Emoji", Font.PLAIN, 15);
    private static final Font LOGICAL = new Font(Font.DIALOG, Font.PLAIN, 15);

    EmojiText() {
        setEditorKit(new StyledEditorKit() {
            @Override public ViewFactory getViewFactory() { return EmojiText::viewFor; }
        });
        setDocument(new EmojiDocument());
        setFont(NORMAL);
        setOpaque(false);
        setBorder(null);
        setMargin(new Insets(0, 0, 0, 0));
        setSelectionColor(ChatTheme.BLUE);
        setSelectedTextColor(Color.WHITE);
        setNavigationFilter(new NavigationFilter() {
            @Override public void setDot(FilterBypass bypass, int dot, Position.Bias bias) {
                bypass.setDot(emojiBoundary(bypass, dot), bias);
            }
            @Override public void moveDot(FilterBypass bypass, int dot, Position.Bias bias) {
                bypass.moveDot(emojiBoundary(bypass, dot), bias);
            }
        });
        deleteEmoji(DefaultEditorKit.deletePrevCharAction, true);
        deleteEmoji(DefaultEditorKit.deleteNextCharAction, false);
    }

    private static View viewFor(Element element) {
        return switch (element.getName()) {
            case AbstractDocument.ParagraphElementName -> new ParagraphView(element);
            case AbstractDocument.SectionElementName -> new BoxView(element, View.Y_AXIS);
            case StyleConstants.IconElementName -> new IconView(element) {
                @Override public float getAlignment(int axis) { return axis == Y_AXIS ? .82f : super.getAlignment(axis); }
            };
            default -> new LabelView(element) {
                @Override public float getMinimumSpan(int axis) { return axis == X_AXIS ? 0 : super.getMinimumSpan(axis); }
            };
        };
    }

    @Override public String getText() {
        try { return getDocument().getText(0, getDocument().getLength()); }
        catch (BadLocationException ex) { throw new IllegalStateException(ex); }
    }

    @Override public void setText(String text) {
        var document = (AbstractDocument) getDocument();
        try { document.replace(0, document.getLength(), text, null); }
        catch (BadLocationException ex) { throw new IllegalStateException(ex); }
    }

    @Override public boolean getScrollableTracksViewportWidth() { return true; }

    int naturalWidth() {
        int maximum = 0, line = 0;
        var matcher = EmojiIcons.GRAPHEME.matcher(getText());
        while (matcher.find()) {
            String value = matcher.group();
            if (value.indexOf('\n') >= 0) {
                maximum = Math.max(maximum, line);
                line = 0;
            } else if (EmojiIcons.supports(value)) {
                line += EmojiIcons.INLINE_SIZE;
            } else {
                line += getFontMetrics(displayFont(value)).stringWidth(value);
            }
        }
        return Math.max(maximum, line);
    }

    private int emojiBoundary(NavigationFilter.FilterBypass bypass, int offset) {
        Element element = emojiAt(offset);
        if (element != null && offset > element.getStartOffset())
            return offset < bypass.getCaret().getDot() ? element.getStartOffset() : element.getEndOffset();
        return offset;
    }

    private Element emojiAt(int offset) {
        if (offset < 0 || offset >= getDocument().getLength()) return null;
        Element element = getStyledDocument().getCharacterElement(offset);
        return StyleConstants.getIcon(element.getAttributes()) == null ? null : element;
    }

    private void deleteEmoji(String key, boolean backward) {
        Action fallback = getActionMap().get(key);
        getActionMap().put(key, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                Element element = emojiAt(getCaretPosition() - (backward ? 1 : 0));
                if (!isEditable() || !isEnabled() || getSelectionStart() != getSelectionEnd() || element == null) {
                    fallback.actionPerformed(event);
                    return;
                }
                try {
                    int start = element.getStartOffset();
                    getDocument().remove(start, element.getEndOffset() - start);
                } catch (BadLocationException ex) { throw new IllegalStateException(ex); }
            }
        });
    }

    private static Font displayFont(String value) {
        if (NORMAL.canDisplayUpTo(value) == -1) return NORMAL;
        return EMOJI_FONT.canDisplayUpTo(value) == -1 ? EMOJI_FONT : LOGICAL;
    }

    private static final class EmojiDocument extends DefaultStyledDocument {
        EmojiDocument() {
            setDocumentFilter(new DocumentFilter() {
                @Override public void insertString(FilterBypass fb, int offset, String value, AttributeSet attrs)
                        throws BadLocationException {
                    fb.insertString(offset, value, attrs);
                    decorate();
                }
                @Override public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
                    fb.remove(offset, length);
                    decorate();
                }
                @Override public void replace(FilterBypass fb, int offset, int length, String value, AttributeSet attrs)
                        throws BadLocationException {
                    fb.replace(offset, length, value, attrs);
                    decorate();
                }
            });
        }

        private void decorate() throws BadLocationException {
            int length = getLength();
            for (int offset = 0; offset < length;) {
                Element span = getCharacterElement(offset);
                // Let the input method finish composing before substituting display glyphs.
                if (span.getAttributes().isDefined(StyleConstants.ComposedTextAttribute)) return;
                offset = span.getEndOffset();
            }
            if (length == 0) return;
            setCharacterAttributes(0, length, SimpleAttributeSet.EMPTY, true);
            var matcher = EmojiIcons.GRAPHEME.matcher(getText(0, length));
            while (matcher.find()) {
                String value = matcher.group();
                Icon icon = EmojiIcons.icon(value, EmojiIcons.INLINE_SIZE);
                Font font = icon == null ? displayFont(value) : NORMAL;
                if (icon == null && font == NORMAL) continue;
                SimpleAttributeSet style = new SimpleAttributeSet();
                if (icon != null) StyleConstants.setIcon(style, icon);
                else StyleConstants.setFontFamily(style, font.getFamily());
                setCharacterAttributes(matcher.start(), value.length(), style, true);
            }
        }
    }
}
