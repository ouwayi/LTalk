package cn.ltalk.client.ui;

import javax.swing.*;
import java.awt.*;

/** A temporary reveal control. Toggling never replaces or modifies the password document. */
public final class PasswordInput extends JPanel {
    private final JPasswordField field;
    private final char mask;
    private final JToggleButton reveal = ChatTheme.choice("显示");
    public PasswordInput(JPasswordField field, String name) {
        super(new BorderLayout(6, 0)); setOpaque(false); this.field = field;
        mask = field.getEchoChar() == 0 ? '\u2022' : field.getEchoChar();
        reveal.setName(name); reveal.setFont(ChatTheme.FONT.deriveFont(11f));
        reveal.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        add(field); add(reveal, BorderLayout.EAST); reveal.addActionListener(e -> setRevealed(reveal.isSelected()));
        setRevealed(false);
    }
    public void setRevealed(boolean value) {
        reveal.setSelected(value); field.setEchoChar(value ? (char) 0 : mask);
        reveal.setText(value ? "隐藏" : "显示");
        reveal.setToolTipText(value ? "隐藏密码" : "显示密码"); reveal.getAccessibleContext().setAccessibleName(reveal.getToolTipText());
    }
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled); if (field != null) field.setEnabled(enabled); if (reveal != null) reveal.setEnabled(enabled);
    }
}
