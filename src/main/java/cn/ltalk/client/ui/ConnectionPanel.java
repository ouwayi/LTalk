package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/** Server transport settings only; account credentials live in AuthenticationPanel. */
public final class ConnectionPanel extends ChatTheme.RoundedPanel {
    public final JTextField host, port;
    public final JButton connect = ChatTheme.primary("连接服务器");
    public final JButton disconnect = ChatTheme.pill("断开连接");
    public final JButton discover = ChatTheme.pill("查找局域网服务器");
    public final JButton recent = ChatTheme.pill("最近连接的服务器");
    public final JCheckBox tls = new JCheckBox("使用 TLS 加密连接");
    public final JLabel status = ChatTheme.label("尚未连接服务器", 12, ChatTheme.MUTED, false);
    public final JComboBox<ServerChoice> choices = new JComboBox<>();
    private final JPanel choiceRow = new JPanel(new BorderLayout(0, 5));
    private final JLabel choiceLabel = ChatTheme.label("", 12, ChatTheme.MUTED, false);

    public record ServerChoice(String name, String host, int port, boolean tls) {
        @Override public String toString() {
            String endpoint = (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
            return (name.isEmpty() ? endpoint : name + " · " + endpoint) + (tls ? " · TLS" : "");
        }
    }

    public ConnectionPanel(String host, String port) {
        super(new GridBagLayout(), 20, ChatTheme.SOFT, false);
        this.host = ChatTheme.field(host == null ? "127.0.0.1" : host, "host");
        this.port = ChatTheme.field(port == null ? "9000" : port, "port");
        connect.setName("connectServer"); disconnect.setName("disconnect"); status.setName("serverStatus");
        discover.setName("discoverServers"); recent.setName("recentServers"); choices.setName("serverChoices");
        tls.setName("useTls"); tls.setOpaque(false);
        tls.setSelected(Boolean.getBoolean("ltalk.client.tls"));
        ChatTheme.foreground(tls, ThemePalette.Role.TEXT);
        setBorder(new EmptyBorder(24, 24, 24, 24));
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        addRow(ChatTheme.label("连接设置", 21, ChatTheme.INK, true), c);
        addRow(ChatTheme.label("服务端 IP 或主机名", 12, ChatTheme.MUTED, false), c); addRow(this.host, c);
        addRow(ChatTheme.label("端口", 12, ChatTheme.MUTED, false), c); addRow(this.port, c);
        JPanel tlsRow = new JPanel(new BorderLayout(0, 4));
        tlsRow.setOpaque(false);
        tlsRow.add(tls, BorderLayout.NORTH);
        tlsRow.add(ChatTheme.formHint(tls, "公网直连请勾选。", "局域网或 VPN 请按服务端设置选择。"));
        addRow(tlsRow, c);
        addRow(connect, c); addRow(disconnect, c); addRow(discover, c); addRow(recent, c);
        ChatTheme.surface(choices); ChatTheme.foreground(choices, ThemePalette.Role.TEXT);
        choices.setPrototypeDisplayValue(new ServerChoice("局域网服务器", "192.168.0.1", 9000, false));
        choices.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                putClientProperty("html.disable", Boolean.TRUE);
                super.getListCellRendererComponent(list, value, index, selected, focus);
                ThemePalette palette = ChatTheme.palette(ConnectionPanel.this);
                setText(value == null ? "请选择服务器" : value.toString());
                setBackground(selected && index >= 0 ? palette.hover() : palette.surface());
                setForeground(palette.text());
                return this;
            }
        });
        choiceRow.setOpaque(false); choiceRow.add(choiceLabel, BorderLayout.NORTH); choiceRow.add(choices);
        choiceRow.setVisible(false); addRow(choiceRow, c); addRow(status, c);
        this.host.setColumns(14); this.port.setColumns(6);
    }
    private void addRow(Component component, GridBagConstraints c) { c.gridy++; c.insets = new Insets(7, 0, 7, 0); add(component, c); }
    public void state(boolean disconnected, boolean connecting) {
        host.setEnabled(disconnected); port.setEnabled(disconnected);
        tls.setEnabled(disconnected);
        connect.setEnabled(disconnected); disconnect.setEnabled(!disconnected);
        discover.setEnabled(disconnected); recent.setEnabled(disconnected);
        choices.setEnabled(disconnected);
        if (!disconnected) clearChoices();
        feedback(disconnected ? "尚未连接服务器" : connecting ? "正在连接服务器…" : "服务器已连接");
    }

    public void scanning(boolean scanning) { discover.setText(scanning ? "取消查找" : "查找局域网服务器"); }

    public void feedback(String detail) {
        status.setText(detail);
        status.setToolTipText(detail);
    }

    public void showChoices(String label, List<ServerChoice> servers) {
        choiceLabel.setText(label);
        choices.getAccessibleContext().setAccessibleName(label);
        DefaultComboBoxModel<ServerChoice> model = new DefaultComboBoxModel<>(servers.toArray(ServerChoice[]::new));
        model.setSelectedItem(null);
        choices.setModel(model);
        choiceRow.setVisible(true);
        relayoutChoices();
    }

    public void clearChoices() {
        choiceRow.setVisible(false);
        choices.setModel(new DefaultComboBoxModel<>());
        relayoutChoices();
    }

    private void relayoutChoices() {
        revalidate();
        if (getParent() != null) getParent().revalidate();
        repaint();
    }
}
