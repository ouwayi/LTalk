package cn.ltalk.client.ui;

import javax.swing.*;
import java.awt.*;

public final class ComposerInput extends EmojiText {
    public ComposerInput() {
        setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
        ChatTheme.foreground(this, ThemePalette.Role.TEXT);
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getDocument().getLength() > 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            ChatTheme.quality(g);
            g.setFont(getFont());
            g.setColor(ChatTheme.palette(this).muted());
            g.drawString("消息", getInsets().left + 2, getInsets().top + g.getFontMetrics().getAscent());
        } finally { g.dispose(); }
    }
}
