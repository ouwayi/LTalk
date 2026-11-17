package cn.ltalk.client.ui;

import javax.swing.text.DefaultCaret;

final class MessageText extends EmojiText {
    MessageText(String text, ThemePalette palette) {
        setEditable(false);
        setForeground(palette.text());
        setCaretColor(palette.text());
        setName("messageText");
        // Building a read-only message must not scroll the timeline to its caret.
        if (getCaret() instanceof DefaultCaret caret) caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        setText(text);
    }
}
