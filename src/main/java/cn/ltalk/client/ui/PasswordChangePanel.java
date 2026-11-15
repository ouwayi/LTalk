package cn.ltalk.client.ui;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/** Authenticated password form. Only matching, nonempty entries are submitted. */
public final class PasswordChangePanel extends JPanel {
    @FunctionalInterface
    public interface Submit {
        void send(String current, String next, Consumer<String> onFailure) throws IOException;
    }

    private final JPasswordField current = field("currentPassword"), next = field("newPassword"),
            confirm = field("confirmNewPassword");
    private final PasswordInput currentInput = new PasswordInput(current, "showCurrentPassword");
    private final PasswordInput nextInput = new PasswordInput(next, "showNewPassword");
    private final PasswordInput confirmInput = new PasswordInput(confirm, "showNewPasswordConfirmation");
    private final JLabel feedback = ChatTheme.label(" ", 12, ChatTheme.MUTED, false);
    private final JButton save = ChatTheme.primary("保存新密码");

    public PasswordChangePanel(Submit submit, Runnable cancelAction) {
        super(new GridBagLayout());
        ChatTheme.surface(this);
        setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        addField("当前密码", current, currentInput, c);
        addField("新密码（6～128 字符）", next, nextInput, c);
        addField("确认新密码", confirm, confirmInput, c);
        feedback.setName("passwordChangeFeedback");
        addRow(feedback, c, 4);
        JButton cancel = ChatTheme.pill("关闭");
        cancel.addActionListener(e -> cancelAction.run());
        save.setName("submitPasswordChange");
        save.addActionListener(e -> submitPassword(submit));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(cancel);
        actions.add(save);
        addRow(actions, c, 8);
    }

    private static JPasswordField field(String name) {
        JPasswordField field = new JPasswordField(20);
        field.setName(name);
        return field;
    }

    private void addField(String title, JPasswordField field, PasswordInput input, GridBagConstraints c) {
        JLabel label = ChatTheme.label(title, 12, ChatTheme.MUTED, false);
        label.setLabelFor(field);
        addRow(label, c, 6);
        addRow(input, c, 3);
    }

    private void addRow(Component component, GridBagConstraints c, int top) {
        c.gridy++;
        c.insets = new Insets(top, 0, 0, 0);
        add(component, c);
    }

    private void submitPassword(Submit submit) {
        char[] oldChars = current.getPassword(), newChars = next.getPassword(), repeated = confirm.getPassword();
        try {
            if (oldChars.length == 0) { failed("请输入当前密码"); return; }
            if (newChars.length < 6 || newChars.length > 128) { failed("新密码须为 6～128 字符"); return; }
            if (!Arrays.equals(newChars, repeated)) { failed("两次输入的新密码不一致"); return; }
            setBusy(true);
            ChatTheme.foreground(feedback, ThemePalette.Role.MUTED);
            feedback.setText("正在修改…");
            clear();
            submit.send(new String(oldChars), new String(newChars), this::failed);
        } catch (IOException ex) {
            failed(ex.getMessage());
        } finally {
            Arrays.fill(oldChars, '\0');
            Arrays.fill(newChars, '\0');
            Arrays.fill(repeated, '\0');
        }
    }

    public JButton submitButton() { return save; }

    private void failed(String detail) {
        ChatTheme.foreground(feedback, ThemePalette.Role.DANGER);
        feedback.setText(detail);
        setBusy(false);
    }

    public void clear() {
        current.setText("");
        next.setText("");
        confirm.setText("");
        currentInput.setRevealed(false);
        nextInput.setRevealed(false);
        confirmInput.setRevealed(false);
    }

    private void setBusy(boolean busy) {
        currentInput.setEnabled(!busy);
        nextInput.setEnabled(!busy);
        confirmInput.setEnabled(!busy);
        save.setEnabled(!busy);
    }
}
