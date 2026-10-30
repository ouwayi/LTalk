package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Server transport settings only; account credentials live in AuthenticationPanel. */
public final class ConnectionPanel extends ChatTheme.RoundedPanel {
    public final JTextField host, port;
    public final JButton connect = ChatTheme.primary("连接服务器");
    public final JButton disconnect = ChatTheme.pill("断开连接");
    public final JLabel status = ChatTheme.label("尚未连接服务器", 12, ChatTheme.MUTED, false);
    public ConnectionPanel(String host, String port) {
        super(new GridBagLayout(), 20, ChatTheme.SOFT, false);
        this.host = ChatTheme.field(host == null ? "127.0.0.1" : host, "host");
        this.port = ChatTheme.field(port == null ? "9000" : port, "port");
        connect.setName("connectServer"); disconnect.setName("disconnect"); status.setName("serverStatus");
        setBorder(new EmptyBorder(24, 24, 24, 24));
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        addRow(ChatTheme.label("连接设置", 21, ChatTheme.INK, true), c);
        addRow(ChatTheme.label("服务端 IP", 12, ChatTheme.MUTED, false), c); addRow(this.host, c);
        addRow(ChatTheme.label("端口", 12, ChatTheme.MUTED, false), c); addRow(this.port, c);
        addRow(connect, c); addRow(disconnect, c); addRow(status, c);
        this.host.setColumns(14); this.port.setColumns(6);
    }
    private void addRow(Component component, GridBagConstraints c) { c.gridy++; c.insets = new Insets(7, 0, 7, 0); add(component, c); }
    public void state(boolean disconnected, boolean connecting) {
        host.setEnabled(disconnected); port.setEnabled(disconnected);
        connect.setEnabled(disconnected); disconnect.setEnabled(!disconnected);
        status.setText(disconnected ? "尚未连接服务器" : connecting ? "正在连接服务器…" : "服务器已连接");
    }
}
