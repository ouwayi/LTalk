package cn.ltalk.client;

import cn.ltalk.common.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ChatWindow extends JFrame implements ClientEventListener {
    private static final Color BLUE = new Color(35, 87, 185);
    private final JTextField host = new JTextField("127.0.0.1", 14);
    private final JTextField port = new JTextField("9000", 5);
    private final JTextField user = new JTextField("", 10);
    private final JButton connect = new JButton("连接");
    private final JButton disconnect = new JButton("断开");
    private final JButton send = new JButton("发送");
    private final JButton group = new JButton("切回群聊");
    private final JLabel status = new JLabel("未连接 · 输入服务端地址和用户标识后连接");
    private final JLabel recipient = new JLabel();
    private final JLabel onlineTitle = new JLabel("在线用户 · 0");
    private final JTextArea transcript = new JTextArea();
    private final JTextArea input = new JTextArea(4, 36);
    private final DefaultListModel<String> userModel = new DefaultListModel<>();
    private final JList<String> online = new JList<>(userModel);
    private final ConversationSelection selection = new ConversationSelection();
    private final ChatClient client = new ChatClient(this, SwingUtilities::invokeLater);
    private List<String> users = List.of();
    private String self = "";
    private boolean updatingUsers;

    public ChatWindow(String initialHost, String initialPort, String initialUser) {
        super("LTalk · 局域网聊天室");
        if (initialHost != null) host.setText(initialHost);
        if (initialPort != null) port.setText(initialPort);
        if (initialUser != null) user.setText(initialUser);
        host.setName("host"); port.setName("port"); user.setName("userId");
        connect.setName("connect"); disconnect.setName("disconnect"); send.setName("send"); group.setName("group");
        input.setName("input"); transcript.setName("transcript"); online.setName("online");
        recipient.setName("recipient"); status.setName("status");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(820, 600));
        setSize(1000, 740);
        setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(new EmptyBorder(18, 18, 14, 18));
        root.setBackground(new Color(245, 247, 251));
        setContentPane(root);

        JPanel header = new JPanel(new BorderLayout(0, 12));
        header.setOpaque(false);
        JLabel title = new JLabel("LTalk  /  局域网聊天室");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setForeground(BLUE);
        header.add(title, BorderLayout.NORTH);
        JPanel connection = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        connection.setOpaque(false);
        connection.add(new JLabel("服务端")); connection.add(host);
        connection.add(new JLabel("端口")); connection.add(port);
        connection.add(new JLabel("用户标识")); connection.add(user);
        connection.add(connect); connection.add(disconnect);
        header.add(connection, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setMargin(new Insets(14, 14, 14, 14));
        transcript.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        transcript.setText("欢迎使用 LTalk。\n连接后默认群聊；选择右侧用户可私聊。消息以服务端回显为准。\n\n");
        JScrollPane history = new JScrollPane(transcript);
        JPanel sidebar = new JPanel(new BorderLayout(0, 10));
        sidebar.setBorder(new EmptyBorder(10, 10, 10, 10));
        sidebar.add(onlineTitle, BorderLayout.NORTH);
        online.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        online.setFixedCellHeight(34);
        online.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                label.setText(value + (value.equals(self) ? "  (我)" : ""));
                return label;
            }
        });
        sidebar.add(new JScrollPane(online), BorderLayout.CENTER);
        sidebar.add(group, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, history, sidebar);
        split.setResizeWeight(0.78);
        split.setDividerLocation(720);
        root.add(split, BorderLayout.CENTER);

        JPanel composer = new JPanel(new BorderLayout(8, 8));
        composer.setOpaque(false);
        recipient.setForeground(BLUE);
        composer.add(recipient, BorderLayout.NORTH);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setMargin(new Insets(8, 10, 8, 10));
        composer.add(new JScrollPane(input), BorderLayout.CENTER);
        send.setBackground(BLUE); send.setForeground(Color.WHITE);
        composer.add(send, BorderLayout.EAST);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(status, BorderLayout.CENTER);
        footer.add(new JLabel("Ctrl+Enter 发送 · 最多 2000 char"), BorderLayout.EAST);
        composer.add(footer, BorderLayout.SOUTH);
        root.add(composer, BorderLayout.SOUTH);

        connect.addActionListener(event -> connect());
        disconnect.addActionListener(event -> client.disconnect());
        send.addActionListener(event -> send());
        group.addActionListener(event -> { online.clearSelection(); selection.selectGroup(); updateSendControls(); });
        online.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || updatingUsers) return;
            String target = online.getSelectedValue();
            if (target == null) return;
            if (target.equals(self)) { online.clearSelection(); return; }
            selection.selectPrivate(target, self, users);
            updateSendControls();
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send-message");
        input.getActionMap().put("send-message", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { if (send.isEnabled()) send(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { client.close(); dispose(); }
        });
        onStateChanged(ChatClient.State.DISCONNECTED, "未连接 · 输入服务端地址和用户标识后连接");
    }

    private void connect() {
        try {
            int number = Integer.parseInt(port.getText().strip());
            self = Validation.userId(user.getText());
            client.connect(host.getText(), number, self);
        } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            showError(ex instanceof NumberFormatException ? "端口必须是 1～65535 的整数" : ex.getMessage());
        }
    }

    private void send() {
        if (!selection.canSend()) { showError("请明确选择私聊对象或点击群聊"); return; }
        try {
            if (selection.target() == null) client.sendGroup(input.getText());
            else client.sendPrivate(selection.target(), input.getText());
            input.setText("");
            input.requestFocusInWindow();
        } catch (IOException ex) { showError(ex.getMessage()); }
    }

    @Override public void onStateChanged(ChatClient.State state, String detail) {
        setTitle("LTalk · 局域网聊天室" + (state == ChatClient.State.CONNECTED ? " · " + self : ""));
        boolean disconnected = state == ChatClient.State.DISCONNECTED;
        host.setEnabled(disconnected); port.setEnabled(disconnected); user.setEnabled(disconnected);
        connect.setEnabled(disconnected); disconnect.setEnabled(!disconnected);
        online.setEnabled(state == ChatClient.State.CONNECTED);
        group.setEnabled(state == ChatClient.State.CONNECTED);
        if (state != ChatClient.State.CONNECTED) replaceUsers(List.of());
        status.setText(detail);
        if (disconnected) append("[系统] " + detail);
        updateSendControls();
    }

    private void updateSendControls() {
        recipient.setText(selection.label());
        send.setEnabled(client.state() == ChatClient.State.CONNECTED && selection.canSend());
    }

    @Override public void onGroup(Message message) {
        append(time(message) + " [群聊] " + message.from() + "\n" + message.content());
    }
    @Override public void onPrivate(Message message) {
        append(time(message) + " [私聊 " + message.from() + " → " + message.to() + "]\n" + message.content());
    }
    @Override public void onUserList(Message message) { replaceUsers(message.users()); }
    @Override public void onSystem(Message message) { append(time(message) + " [系统] " + message.content()); }
    @Override public void onError(Message message) { showError(message.code() + "：" + message.message()); }

    private void replaceUsers(List<String> replacement) {
        users = List.copyOf(replacement);
        if (selection.updateUsers(users)) append("[系统] 私聊对象已离线，草稿保留；请明确重新选择发送目标。");
        updatingUsers = true;
        try {
            userModel.clear();
            users.forEach(userModel::addElement);
            if (selection.target() != null) online.setSelectedValue(selection.target(), true);
        } finally { updatingUsers = false; }
        onlineTitle.setText("在线用户 · " + users.size());
        updateSendControls();
    }

    private void showError(String detail) { status.setText(detail); append("[错误] " + detail); }
    private void append(String text) { transcript.append(text + "\n\n"); transcript.setCaretPosition(transcript.getDocument().getLength()); }
    private static String time(Message message) {
        try { return DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.parse(message.serverTime()).atZone(ZoneId.systemDefault())); }
        catch (RuntimeException ex) { return "--:--:--"; }
    }

    public static void main(String[] args) {
        if (args.length > 3) {
            System.err.println("用法：java -cp target/ltalk-1.0.0.jar cn.ltalk.client.ChatWindow [服务端IP] [端口] [用户标识]");
            System.exit(1);
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("桌面客户端需要图形桌面环境。"); System.exit(1);
        }
        SwingUtilities.invokeLater(() -> {
            UIManager.put("defaultFont", new Font("Microsoft YaHei", Font.PLAIN, 13));
            for (Object key : UIManager.getDefaults().keySet().toArray()) {
                if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)
                    UIManager.put(key, new javax.swing.plaf.FontUIResource("Microsoft YaHei", Font.PLAIN, 13));
            }
            new ChatWindow(args.length > 0 ? args[0] : null, args.length > 1 ? args[1] : null,
                    args.length > 2 ? args[2] : null).setVisible(true);
        });
    }
}
