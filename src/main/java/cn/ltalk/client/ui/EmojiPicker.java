package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public final class EmojiPicker extends JPopupMenu {
    public EmojiPicker(ThemePalette palette, Consumer<String> selected) {
        setName("emojiPicker");
        setLabel("选择 Emoji");
        setBorder(BorderFactory.createLineBorder(palette.line()));
        JPanel grid = new JPanel(new GridLayout(0, 9, 2, 2));
        grid.setBorder(new EmptyBorder(5, 5, 5, 5));
        ChatTheme.surface(grid);
        for (EmojiIcons.Choice item : EmojiIcons.CHOICES) {
            JButton choice = ChatTheme.iconButton(LineIcon.Kind.EMOJI, item.label());
            choice.setName("emoji-" + item.value());
            choice.setIcon(EmojiIcons.icon(item.value(), 25));
            choice.setBorder(new EmptyBorder(0, 0, 0, 0));
            choice.setPreferredSize(new Dimension(31, 31));
            choice.addActionListener(e -> {
                setVisible(false);
                selected.accept(item.value());
            });
            grid.add(choice);
        }
        add(grid);
        ChatTheme.apply(this, palette);
    }
}
