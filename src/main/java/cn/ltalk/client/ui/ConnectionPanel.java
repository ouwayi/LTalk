package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Connection form only: callers retain the existing connect/disconnect operations. */
public final class ConnectionPanel extends ChatTheme.RoundedPanel {
    public final JTextField host, port, user;
    public final JPasswordField password = new JPasswordField();
    public final JButton register = ChatTheme.pill("注册账号");
    public final JButton connect = ChatTheme.primary("连接聊天室");
    public final JButton disconnect = ChatTheme.iconButton(LineIcon.Kind.LOGOUT, "断开当前连接");
    public ConnectionPanel(String host, String port, String user) {
        super(new GridBagLayout(), 20, ChatTheme.SOFT, false);
        this.host = ChatTheme.field(host == null ? "127.0.0.1" : host, "host");
        this.port = ChatTheme.field(port == null ? "9000" : port, "port");
        this.user = ChatTheme.field(user == null ? "" : user, "userId");
        connect.setName("connect"); disconnect.setName("disconnect"); setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints(); c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        c.insets = new Insets(3, 3, 3, 3); c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        add(ChatTheme.label("连接到你的局域网", 13, ChatTheme.INK, true), c);
        c.gridy++; c.gridwidth = 1; c.weightx = .75; add(ChatTheme.label("服务端 IP", 10, ChatTheme.MUTED, false), c);
        c.gridx = 1; c.weightx = .25; add(ChatTheme.label("端口", 10, ChatTheme.MUTED, false), c);
        c.gridy++; c.gridx = 0; c.weightx = .75; this.host.setColumns(10); add(this.host, c);
        c.gridx = 1; c.weightx = .25; this.port.setColumns(4); add(this.port, c);
        c.gridy++; c.gridx = 0; c.gridwidth = 2; add(ChatTheme.label("用户标识 · 字母、数字或下划线", 10, ChatTheme.MUTED, false), c);
        c.gridy++; add(this.user, c);
        c.gridy++; add(ChatTheme.label("密码", 10, ChatTheme.MUTED, false), c);
        c.gridy++; password.setName("password"); add(password, c);
        c.gridy++; register.setName("register"); add(register, c);
        JPanel actions = new JPanel(new BorderLayout(6, 0)); actions.setOpaque(false); actions.add(connect); actions.add(disconnect, BorderLayout.EAST);
        c.gridy++; c.insets = new Insets(10, 3, 2, 3); add(actions, c);
    }
    public void state(boolean disconnected) {
        password.setEnabled(disconnected); register.setEnabled(disconnected);
        host.setEnabled(disconnected); port.setEnabled(disconnected); user.setEnabled(disconnected);
        connect.setEnabled(disconnected); disconnect.setEnabled(!disconnected);
    }
}
