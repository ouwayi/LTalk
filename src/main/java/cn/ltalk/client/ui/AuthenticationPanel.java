package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Switches login/registration fields without starting any network operation. */
public final class AuthenticationPanel extends JPanel {
    public final JTextField user, nickname = ChatTheme.field("", "nickname");
    public final JPasswordField password = new JPasswordField(), confirmation = new JPasswordField();
    public final JCheckBox random = new JCheckBox("随机生成用户名");
    public final JButton submit = ChatTheme.primary("登录"), switchMode = ChatTheme.pill("立即注册");
    public final JLabel feedback = ChatTheme.label("请先连接左侧服务器", 12, ChatTheme.MUTED, false);
    private final JLabel heading = ChatTheme.label("登录 LTalk", 23, ChatTheme.INK, true);
    private final JLabel switchHint = ChatTheme.label("还没有账号？", 12, ChatTheme.MUTED, false);
    private final JPanel nicknameRow, confirmationRow, randomRow;
    private boolean registering, available, busy;
    public AuthenticationPanel(String initialUser) {
        super(new GridBagLayout()); ChatTheme.surface(this); setBorder(new EmptyBorder(24, 24, 24, 24));
        setName("authenticationPanel"); user = ChatTheme.field(initialUser == null ? "" : initialUser, "userId");
        password.setName("password"); confirmation.setName("passwordConfirmation");
        stylePassword(password); stylePassword(confirmation); random.setName("randomUsername"); random.setOpaque(false);
        ChatTheme.foreground(random, ThemePalette.Role.TEXT);
        submit.setName("connect"); switchMode.setName("register"); feedback.setName("authFeedback");
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        addRow(heading, c); addRow(field("用户名", user), c);
        randomRow = new JPanel(new BorderLayout()); randomRow.setOpaque(false); randomRow.add(random); addRow(randomRow, c);
        nicknameRow = field("昵称", nickname); addRow(nicknameRow, c);
        addRow(field("密码", password), c); confirmationRow = field("确认密码", confirmation); addRow(confirmationRow, c);
        addRow(submit, c);
        JPanel links = new JPanel(new FlowLayout(FlowLayout.CENTER, 5, 0)); links.setOpaque(false);
        links.add(switchHint); links.add(switchMode); addRow(links, c); addRow(feedback, c);
        switchMode.addActionListener(e -> setRegistering(!registering)); random.addActionListener(e -> update());
        setRegistering(false);
    }
    private static void stylePassword(JPasswordField field) {
        field.setFont(ChatTheme.FONT); field.setBorder(new EmptyBorder(10, 12, 10, 12));
        ChatTheme.foreground(field, ThemePalette.Role.TEXT);
        field.putClientProperty("ltalk.background", ThemePalette.Role.SOFT);
    }
    private static JPanel field(String title, JComponent input) {
        JPanel row = new JPanel(new BorderLayout(0, 6)); row.setOpaque(false);
        row.add(ChatTheme.label(title, 12, ChatTheme.MUTED, false), BorderLayout.NORTH); row.add(input); return row;
    }
    private void addRow(Component component, GridBagConstraints c) { c.gridy++; c.insets = new Insets(6, 0, 6, 0); add(component, c); }
    public boolean registering() { return registering; }
    public void setRegistering(boolean value) {
        registering = value; password.setText(""); confirmation.setText("");
        nicknameRow.setVisible(value); confirmationRow.setVisible(value); randomRow.setVisible(value);
        heading.setText(value ? "注册账号" : "登录 LTalk"); submit.setText(value ? "注册并登录" : "登录");
        switchHint.setText(value ? "已有账号？" : "还没有账号？"); switchMode.setText(value ? "立即登录" : "立即注册");
        feedback.setText(available ? " " : "请先连接左侧服务器"); update(); revalidate(); repaint();
    }
    public void state(boolean available, boolean busy) { this.available = available; this.busy = busy; update(); }
    private void update() {
        submit.setEnabled(available && !busy); switchMode.setEnabled(!busy);
        user.setEnabled(!busy && !(registering && random.isSelected()));
        password.setEnabled(!busy); nickname.setEnabled(!busy); confirmation.setEnabled(!busy); random.setEnabled(!busy);
    }
    public void feedback(String text, boolean error) {
        feedback.setText(text); feedback.setToolTipText(text);
        ChatTheme.foreground(feedback, error ? ThemePalette.Role.DANGER : ThemePalette.Role.MUTED);
    }
    public void clearCredentials(boolean clearUser) {
        password.setText(""); confirmation.setText(""); nickname.setText(""); random.setSelected(false);
        if (clearUser) user.setText(""); setRegistering(false);
    }
}
