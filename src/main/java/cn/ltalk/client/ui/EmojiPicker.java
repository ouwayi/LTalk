package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public final class EmojiPicker extends JPopupMenu {
    private static final String[] EMOJI = {
            "😀", "😂", "😊", "🥰", "😎", "🤔",
            "😅", "😭", "😮", "😴", "🙃", "😤",
            "👍", "👎", "👏", "🙌", "🙏", "👋",
            "❤️", "🔥", "🎉", "⭐", "✅", "💡"
    };

    public EmojiPicker(ThemePalette palette, Consumer<String> selected) {
        setName("emojiPicker");
        setLabel("选择 Emoji");
        JPanel grid = new JPanel(new GridLayout(0, 6, 3, 3));
        grid.setBorder(new EmptyBorder(5, 5, 5, 5));
        ChatTheme.surface(grid);
        Font emojiFont = new Font("Segoe UI Emoji", Font.PLAIN, 20);
        Font fallback = new Font(Font.DIALOG, Font.PLAIN, 20);
        for (String value : EMOJI) {
            JButton choice = ChatTheme.pill(value);
            choice.setName("emoji-" + value);
            choice.setFont(emojiFont.canDisplay(value.codePointAt(0)) ? emojiFont : fallback);
            choice.setBorder(new EmptyBorder(0, 0, 0, 0));
            choice.setPreferredSize(new Dimension(33, 33));
            choice.addActionListener(e -> {
                setVisible(false);
                selected.accept(value);
            });
            grid.add(choice);
        }
        add(grid);
        ChatTheme.apply(this, palette);
    }
}
