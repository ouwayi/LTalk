package cn.ltalk.client;

import cn.ltalk.client.ui.*;
import cn.ltalk.common.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Swing presentation adapter. The TCP client, server interfaces and wire protocol are unchanged. */
public final class ChatWindow extends JFrame implements ClientEventListener {
    private final ConversationStore store = new ConversationStore();
    private final ChatClient client = new ChatClient(this, SwingUtilities::invokeLater);
    private final ConnectionPanel connection;
    private final JTextField search = ChatTheme.field("", "conversationSearch");
    private final JTextField messageSearch = ChatTheme.field("", "messageSearch");
    private final JTextArea input = new JTextArea(1, 16) {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty()) {
                Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g); g.setFont(getFont()); g.setColor(ChatTheme.palette(this).muted());
                g.drawString("消息", getInsets().left + 2, getInsets().top + g.getFontMetrics().getAscent()); g.dispose();
            }
        }
    };
    private final JLabel status = ChatTheme.label("未连接 · 点击连接聊天室开始", 11, ChatTheme.MUTED, false);
    private final JLabel recipient = ChatTheme.label("群聊 · 所有在线用户", 12, ChatTheme.MUTED, false);
    private final JLabel title = ChatTheme.label("公共聊天室", 17, ChatTheme.INK, true);
    private final JLabel headerAvatar = new JLabel();
    private final JLabel onlineTitle = ChatTheme.label("消息", 12, ChatTheme.MUTED, false);
    private final JLabel counter = ChatTheme.label("", 10, ChatTheme.MUTED, false);
    private final JLabel hint = ChatTheme.label("Enter 发送 · Shift+Enter 换行", 10, ChatTheme.MUTED, false);
    private final JLabel identity = ChatTheme.label("LTalk · 未连接", 12, ChatTheme.INK, true);
    private final JButton send = ChatTheme.primary("");
    private final JButton group = ChatTheme.pill("公共聊天室");
    private final JButton back = ChatTheme.iconButton(LineIcon.Kind.BACK, "返回会话列表");
    private final JButton menu = ChatTheme.iconButton(LineIcon.Kind.MENU, "菜单与连接设置");
    private final JButton create = ChatTheme.iconButton(LineIcon.Kind.COMPOSE, "新建会话");
    private final JButton chatSearch = ChatTheme.iconButton(LineIcon.Kind.SEARCH, "搜索当前会话消息");
    private final JButton more = ChatTheme.iconButton(LineIcon.Kind.MORE, "会话更多操作");
    private final JButton attach = ChatTheme.iconButton(LineIcon.Kind.ATTACH, "附件 · 暂未接入");
    private final JButton emoji = ChatTheme.iconButton(LineIcon.Kind.EMOJI, "表情面板 · 暂未接入");
    private final DefaultListModel<ConversationStore.Summary> listModel = new DefaultListModel<>();
    private final ConversationList conversations = new ConversationList(listModel);
    private final MessageTimeline timeline = new MessageTimeline();
    private final JPanel listCards = new JPanel(new CardLayout());
    private final JPanel historySearch = new JPanel(new BorderLayout(6, 0));
    private final JPanel sidebar = new JPanel(new BorderLayout());
    private final ChatContent chatContent = new ChatContent();
    private final ResponsiveShell shell;
    private final JScrollPane editorScroll;
    private final ChatTheme.RoundedPanel header;
    private final ChatTheme.RoundedPanel composer;
    private JPanel connectionWrapper;
    private ChatClient.State connectionState = ChatClient.State.DISCONNECTED;
    private boolean refreshingList, restoringDraft;
    private String pendingSelf = "";
    private final AppearancePreferences appearancePreferences;
    private AppearanceSettings appearance;
    private final WallpaperPanel background;

    public ChatWindow(String initialHost, String initialPort, String initialUser) {
        this(initialHost, initialPort, initialUser, AppearancePreferences.defaultFile());
    }
    ChatWindow(String initialHost, String initialPort, String initialUser, Path appearanceFile) {
        super("LTalk · 局域网聊天室");
        appearancePreferences = new AppearancePreferences(appearanceFile); appearance = appearancePreferences.load();
        connection = new ConnectionPanel(initialHost, initialPort, initialUser);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(360, 540)); setSize(1200, 820); setLocationByPlatform(true);
        status.setName("status"); recipient.setName("recipient"); title.setName("conversationTitle");
        send.setName("send"); group.setName("group"); back.setName("back"); menu.setName("menu");
        create.setName("createConversation"); attach.setName("attach"); emoji.setName("emoji"); input.setName("input");
        hint.setName("composerHint");
        chatSearch.setName("chatSearch"); more.setName("chatMore");
        buildSidebar();
        search.putClientProperty("placeholder", "搜索"); messageSearch.putClientProperty("placeholder", "搜索当前会话消息");
        connection.user.putClientProperty("placeholder", "例如 alice");

        header = new ChatTheme.RoundedPanel(new BorderLayout(8, 0), 24, Color.WHITE, true);
        header.setBorder(new EmptyBorder(10, 10, 10, 12));
        JPanel contact = new JPanel(new BorderLayout(10, 0)); contact.setOpaque(false);
        JPanel contactStart = new JPanel(new BorderLayout(6, 0)); contactStart.setOpaque(false);
        contactStart.add(back, BorderLayout.WEST); contactStart.add(headerAvatar, BorderLayout.CENTER);
        contact.add(contactStart, BorderLayout.WEST);
        JPanel names = new JPanel(new GridLayout(2, 1, 0, 1)); names.setOpaque(false); names.add(title); names.add(recipient);
        contact.add(names, BorderLayout.CENTER); header.add(contact, BorderLayout.CENTER);
        JPanel tools = new JPanel(new GridLayout(1, 2, 2, 0)); tools.setOpaque(false); tools.add(chatSearch); tools.add(more);
        header.add(tools, BorderLayout.EAST);
        historySearch.setOpaque(false); historySearch.setBorder(new EmptyBorder(8, 38, 0, 8));
        messageSearch.setToolTipText("在当前会话的已接收消息中搜索");
        JButton closeSearch = ChatTheme.iconButton(LineIcon.Kind.CLOSE, "关闭消息搜索");
        closeSearch.addActionListener(e -> { historySearch.setVisible(false); messageSearch.setText(""); chatContent.revalidate(); });
        historySearch.add(messageSearch); historySearch.add(closeSearch, BorderLayout.EAST); historySearch.setVisible(false);
        header.add(historySearch, BorderLayout.SOUTH);

        input.setFont(ChatTheme.FONT.deriveFont(15f)); ChatTheme.foreground(input, ThemePalette.Role.TEXT);
        input.setLineWrap(true); input.setWrapStyleWord(true); input.setOpaque(false);
        input.setBorder(new EmptyBorder(4, 2, 4, 2)); input.setMargin(new Insets(0, 0, 0, 0));
        input.setToolTipText("输入消息；Enter 发送，Shift+Enter 换行");
        editorScroll = new JScrollPane(input); ChatTheme.scroll(editorScroll, true);
        composer = new ChatTheme.RoundedPanel(new BorderLayout(8, 0), 26, Color.WHITE, true);
        composer.setBorder(new EmptyBorder(8, 8, 8, 8)); composer.add(attach, BorderLayout.WEST); composer.add(editorScroll, BorderLayout.CENTER);
        JPanel composeActions = new JPanel(new BorderLayout(4, 0)); composeActions.setOpaque(false);
        composeActions.add(emoji, BorderLayout.WEST); composeActions.add(send, BorderLayout.EAST);
        send.setIcon(new LineIcon(LineIcon.Kind.SEND, Color.WHITE, 23)); send.setPreferredSize(new Dimension(44, 44));
        send.setToolTipText("发送消息"); send.getAccessibleContext().setAccessibleName("发送消息");
        composer.add(composeActions, BorderLayout.EAST);
        chatContent.add(header); chatContent.add(timeline); chatContent.add(composer); chatContent.add(hint); chatContent.add(counter);
        background = new WallpaperPanel(new BorderLayout(), appearance); background.setName("chatPane"); background.add(chatContent);
        shell = new ResponsiveShell(sidebar, background); setContentPane(shell);
        shell.onLayout(() -> {
            back.setVisible(shell.compact());
            int before = store.selected().unread();
            store.setVisible(background.isVisible() && (!isVisible() || isFocused()));
            if (before != store.selected().unread()) SwingUtilities.invokeLater(this::refreshList);
        });

        connection.connect.addActionListener(e -> connect()); connection.disconnect.addActionListener(e -> client.disconnect());
        send.addActionListener(e -> send()); group.addActionListener(e -> selectConversation(ConversationId.PUBLIC));
        back.addActionListener(e -> { shell.openList(); refreshList(); });
        menu.addActionListener(e -> showMenu()); create.addActionListener(e -> showNewConversation()); more.addActionListener(e -> showChatMenu());
        chatSearch.addActionListener(e -> {
            historySearch.setVisible(!historySearch.isVisible());
            if (historySearch.isVisible()) messageSearch.requestFocusInWindow(); else messageSearch.setText("");
            chatContent.revalidate(); chatContent.repaint();
        });
        attach.addActionListener(e -> showPendingTool("图片与文件附件"));
        emoji.addActionListener(e -> showPendingTool("表情选择面板"));
        conversations.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !refreshingList && conversations.getSelectedValue() != null)
                selectConversation(conversations.getSelectedValue().id());
        });
        conversations.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int index = conversations.locationToIndex(e.getPoint());
                if (index >= 0 && conversations.getCellBounds(index, index).contains(e.getPoint())) selectConversation(listModel.get(index).id());
            }
        });
        conversations.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-conversation");
        conversations.getActionMap().put("open-conversation", action(() -> {
            if (conversations.getSelectedValue() != null) selectConversation(conversations.getSelectedValue().id());
        }));
        search.getDocument().addDocumentListener(changed(this::refreshList));
        messageSearch.getDocument().addDocumentListener(changed(this::renderTimeline));
        input.getDocument().addDocumentListener(changed(() -> {
            if (!restoringDraft) store.saveDraft(input.getText());
            counter.setText(input.getText().length() + " / 2000");
            ChatTheme.foreground(counter, input.getText().length() > 2000 ? ThemePalette.Role.DANGER : ThemePalette.Role.MUTED);
            chatContent.revalidate(); chatContent.repaint();
        }));
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send-message");
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        input.getActionMap().put("send-message", action(() -> { if (send.isEnabled()) send(); }));
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { store.saveDraft(input.getText()); client.close(); dispose(); }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) { store.setVisible(!shell.compact() || shell.chatOpen()); refreshList(); }
            @Override public void windowLostFocus(WindowEvent e) { store.setVisible(false); }
        });
        connection.state(true); applyAppearance(appearance, false); refreshList(); refreshConversation();
    }

    private void buildSidebar() {
        ChatTheme.surface(sidebar); sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, ChatTheme.LINE)); sidebar.setName("sidebar");
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS)); ChatTheme.surface(top);
        JPanel searchRow = new JPanel(new BorderLayout(5, 0)); ChatTheme.surface(searchRow); searchRow.setBorder(new EmptyBorder(14, 8, 8, 8));
        ChatTheme.RoundedPanel searchPill = new ChatTheme.RoundedPanel(new BorderLayout(6, 0), 32, ChatTheme.SOFT, false);
        searchPill.setBorder(new EmptyBorder(0, 10, 0, 8)); searchPill.add(new JLabel(new LineIcon(LineIcon.Kind.SEARCH, ChatTheme.MUTED, 18)), BorderLayout.WEST);
        search.setBorder(new EmptyBorder(10, 0, 10, 0)); search.setOpaque(false); search.setToolTipText("搜索会话名称或最后一条消息");
        search.getAccessibleContext().setAccessibleName("搜索会话"); searchPill.add(search);
        searchRow.add(menu, BorderLayout.WEST); searchRow.add(searchPill); searchRow.add(create, BorderLayout.EAST); top.add(searchRow);
        JPanel brand = new JPanel(new BorderLayout()); ChatTheme.surface(brand); brand.setBorder(new EmptyBorder(4, 21, 10, 19));
        brand.add(ChatTheme.label("LTalk", 22, ChatTheme.INK, true)); brand.add(onlineTitle, BorderLayout.EAST); top.add(brand);
        connectionWrapper = new JPanel(new BorderLayout()); ChatTheme.surface(connectionWrapper); connectionWrapper.setBorder(new EmptyBorder(0, 14, 10, 14));
        connectionWrapper.add(connection); top.add(connectionWrapper);
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4)); ChatTheme.surface(tabs);
        group.setFocusPainted(false); group.setBorder(new EmptyBorder(8, 14, 8, 14)); group.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        group.setToolTipText("选择公共群聊"); tabs.add(group); tabs.add(ChatTheme.label("全部会话", 11, ChatTheme.MUTED, false)); top.add(tabs);
        sidebar.add(top, BorderLayout.NORTH);
        JScrollPane listScroll = new JScrollPane(conversations); ChatTheme.scroll(listScroll, false);
        ChatTheme.surface(listCards); listCards.add(listScroll, "list");
        JPanel empty = new JPanel(new GridBagLayout()); ChatTheme.surface(empty); empty.add(ChatTheme.label("没有匹配的会话", 13, ChatTheme.MUTED, false)); listCards.add(empty, "empty");
        sidebar.add(listCards, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(4, 5)); ChatTheme.surface(footer); footer.setBorder(new EmptyBorder(11, 20, 13, 14));
        footer.add(identity, BorderLayout.NORTH); footer.add(status, BorderLayout.CENTER);
        JButton settings = ChatTheme.iconButton(LineIcon.Kind.MORE, "显示连接设置"); settings.setName("connectionSettings");
        settings.addActionListener(e -> toggleConnection()); footer.add(settings, BorderLayout.EAST); sidebar.add(footer, BorderLayout.SOUTH);
    }

    private void connect() {
        try {
            pendingSelf = Validation.userId(connection.user.getText());
            client.connect(connection.host.getText(), Integer.parseInt(connection.port.getText().strip()), pendingSelf);
        } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            showError(ex instanceof NumberFormatException ? "端口必须是 1～65535 的整数" : ex.getMessage());
        }
    }
    private void send() {
        if (!store.canSend()) { showError("请明确重新选择在线私聊对象或公共聊天室"); return; }
        ConversationId submittedTo = store.selectedId();
        try {
            if (submittedTo.kind() == ConversationId.Kind.GROUP) client.sendGroup(input.getText());
            else client.sendPrivate(submittedTo.key(), input.getText());
            input.setText(""); store.saveDraft(""); input.requestFocusInWindow(); timeline.scrollToBottom();
            hint.setText("Enter 发送 · Shift+Enter 换行"); ChatTheme.foreground(hint, ThemePalette.Role.MUTED);
        } catch (IOException ex) { showError(ex.getMessage()); }
    }

    private void selectConversation(ConversationId id) {
        store.saveDraft(input.getText()); store.select(id); shell.openChat();
        messageSearch.setText(""); refreshList(); refreshConversation(); input.requestFocusInWindow();
    }
    private void refreshConversation() {
        ConversationStore.Summary selected = store.selected();
        title.setText(selected.title()); headerAvatar.setIcon(new Avatar(selected.title(), selected.id().kind() == ConversationId.Kind.GROUP, 42));
        recipient.setText(store.targetLabel() + (selected.id().kind() == ConversationId.Kind.GROUP ? " · " + store.onlineCount() + " 人在线" : ""));
        recipient.setToolTipText(recipient.getText());
        restoringDraft = true;
        try { input.setText(store.draft()); } finally { restoringDraft = false; }
        send.setEnabled(connectionState == ChatClient.State.CONNECTED && store.canSend());
        input.setToolTipText(store.canSend() ? "消息 · Enter 发送，Shift+Enter 换行" : "草稿已保留；请明确重新选择在线联系人或群聊");
        renderTimeline();
    }
    private void renderTimeline() { timeline.showEntries(store.entries(), store.self(), messageSearch.getText(), connectionState == ChatClient.State.CONNECTED); }
    private void refreshList() {
        refreshingList = true;
        try {
            listModel.clear(); List<ConversationStore.Summary> summaries = store.summaries(search.getText());
            for (ConversationStore.Summary summary : summaries) listModel.addElement(summary);
            for (int i = 0; i < listModel.size(); i++) if (listModel.get(i).id().equals(store.selectedId())) conversations.setSelectedIndex(i);
            ((CardLayout) listCards.getLayout()).show(listCards, listModel.isEmpty() ? "empty" : "list");
        } finally { refreshingList = false; }
        onlineTitle.setText(store.onlineCount() + " 人在线"); conversations.repaint();
    }

    @Override public void onStateChanged(ChatClient.State state, String detail) {
        connectionState = state;
        if (state == ChatClient.State.CONNECTED) {
            store.saveDraft(input.getText()); store.useIdentity(pendingSelf); showConnection(false);
        } else {
            boolean lost = store.updateUsers(List.of());
            if (lost) store.notice(store.selectedId(), "私聊对象已离线，草稿保留；请重新选择会话。", false);
            if (state == ChatClient.State.DISCONNECTED) { store.notice(store.selectedId(), detail, false); showConnection(true); }
        }
        connection.state(state == ChatClient.State.DISCONNECTED);
        identity.setText(state == ChatClient.State.CONNECTED ? "●  " + store.self() + "  ·  已连接" : state == ChatClient.State.CONNECTING ? "◌  正在连接…" : "LTalk · 未连接");
        ChatTheme.foreground(identity, state == ChatClient.State.CONNECTED ? ThemePalette.Role.ACCENT : ThemePalette.Role.TEXT);
        setTitle("LTalk · 局域网聊天室" + (state == ChatClient.State.CONNECTED ? " · " + store.self() : ""));
        setStatus(detail, false); refreshList(); refreshConversation();
    }
    @Override public void onGroup(Message message) { receiveChat(message); }
    @Override public void onPrivate(Message message) { receiveChat(message); }
    private void receiveChat(Message message) {
        ConversationId destination = store.receive(message); refreshList();
        if (destination.equals(store.selectedId())) renderTimeline();
    }
    @Override public void onUserList(Message message) {
        if (store.updateUsers(message.users())) store.notice(store.selectedId(), "私聊对象已离线，草稿保留；请重新选择会话。", false);
        refreshList(); refreshConversation();
    }
    @Override public void onSystem(Message message) {
        store.system(message.content()); if (store.selectedId().equals(ConversationId.PUBLIC)) renderTimeline();
    }
    @Override public void onError(Message message) {
        ConversationId target = store.error(message); feedback(message.code() + "：" + message.message(), true);
        if (target.equals(store.selectedId())) renderTimeline();
    }
    private void showError(String detail) { store.notice(store.selectedId(), detail, true); feedback(detail, true); renderTimeline(); }
    private void feedback(String detail, boolean error) {
        setStatus(detail, error); hint.setText(detail); hint.setToolTipText(detail);
        ChatTheme.foreground(hint, error ? ThemePalette.Role.DANGER : ThemePalette.Role.MUTED);
    }
    private void setStatus(String detail, boolean error) {
        status.setText(detail); status.setToolTipText(detail); ChatTheme.foreground(status, error ? ThemePalette.Role.DANGER : ThemePalette.Role.MUTED);
    }

    private void showConnection(boolean visible) { connection.setVisible(visible); connectionWrapper.setVisible(visible); sidebar.revalidate(); sidebar.repaint(); }
    private void toggleConnection() { showConnection(!connection.isVisible()); shell.openList(); }
    private void showMenu() {
        ChatPopupMenu popup = new ChatPopupMenu(menu, "LTalk");
        popup.action("menuConnection", "连接设置", LineIcon.Kind.SETTINGS, this::toggleConnection);
        popup.action("menuAppearance", "外观设置", LineIcon.Kind.PALETTE, this::showAppearance);
        popup.action("menuMode", appearance.dark() ? "切换浅色模式" : "切换深色模式", appearance.dark() ? LineIcon.Kind.SUN : LineIcon.Kind.MOON,
                () -> applyAppearance(appearance.withMode(appearance.dark() ? AppearanceSettings.Mode.LIGHT : AppearanceSettings.Mode.DARK), true));
        popup.addSeparator();
        JMenuItem disconnect = popup.action("menuDisconnect", "断开连接", LineIcon.Kind.LOGOUT, client::disconnect);
        disconnect.setEnabled(connectionState != ChatClient.State.DISCONNECTED);
        popup.action("menuAbout", "关于 LTalk", LineIcon.Kind.USERS, () -> setStatus("LTalk · 局域网聊天，群聊与私聊由服务端转发", false));
        popup.show(menu, 0, menu.getHeight() + 4);
    }
    private void showNewConversation() {
        ChatPopupMenu popup = new ChatPopupMenu(create, "新建会话");
        popup.action("newPublic", "进入公共聊天室", LineIcon.Kind.USERS, () -> selectConversation(ConversationId.PUBLIC));
        for (ConversationStore.Summary summary : store.summaries("")) {
            if (summary.id().kind() == ConversationId.Kind.PRIVATE && summary.online())
                popup.action("newPrivate-" + summary.id().key(), "私聊 " + summary.title(), LineIcon.Kind.COMPOSE, () -> selectConversation(summary.id()));
        }
        popup.addSeparator(); popup.action("newFuture", "添加好友 / 创建群组 · 待接入", LineIcon.Kind.USERS, () -> showPendingTool("好友与群组管理"));
        popup.show(create, 0, create.getHeight() + 4);
    }
    private void showChatMenu() {
        ChatPopupMenu popup = new ChatPopupMenu(more, store.selected().title());
        popup.action("chatLatest", "回到最新消息", LineIcon.Kind.BACK, timeline::scrollToBottom);
        popup.action("chatAppearance", "外观设置", LineIcon.Kind.PALETTE, this::showAppearance);
        popup.action("chatConnection", "连接设置", LineIcon.Kind.SETTINGS, this::toggleConnection);
        popup.show(more, more.getWidth() - popup.getPreferredSize().width, more.getHeight() + 4);
    }
    private void showAppearance() {
        JDialog dialog = new JDialog(this, "外观设置", Dialog.ModalityType.APPLICATION_MODAL); dialog.setName("appearanceDialog");
        AppearancePanel panel = new AppearancePanel(appearance, value -> { applyAppearance(value, true); dialog.dispose(); }, dialog::dispose);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); dialog.setContentPane(panel); dialog.pack();
        dialog.setResizable(false); dialog.setLocationRelativeTo(this);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setVisible(true);
    }
    void applyAppearance(AppearanceSettings value, boolean persist) {
        appearance = value; ThemePalette palette = ThemePalette.of(value); background.setAppearance(value);
        ChatTheme.apply(shell, palette); sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, palette.line()));
        conversations.repaint(); renderTimeline();
        if (persist) {
            try { appearancePreferences.save(value); setStatus("外观设置已保存到本机", false); }
            catch (IOException ex) { feedback("外观已应用，但本地保存失败：" + ex.getMessage(), true); }
        }
    }
    /** Integration point for later attachment/emoji/friend features; does not send fake wire requests. */
    private void showPendingTool(String feature) { feedback(feature + "尚未接入；当前支持纯文本消息", false); }
    private static Action action(Runnable callback) { return new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { callback.run(); } }; }
    private static DocumentListener changed(Runnable task) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { task.run(); }
            @Override public void removeUpdate(DocumentEvent e) { task.run(); }
            @Override public void changedUpdate(DocumentEvent e) { task.run(); }
        };
    }

    private final class ChatContent extends JPanel {
        ChatContent() { super(null); setOpaque(false); }
        @Override public void doLayout() {
            if (header == null) return;
            int inset = getWidth() < 500 ? 10 : 26;
            int width = Math.max(260, Math.min(920, getWidth() - inset * 2)); int x = (getWidth() - width) / 2;
            int top = getWidth() < 500 ? 12 : 24; int headerHeight = historySearch.isVisible() ? 112 : 70;
            header.setBounds(x, top, width, headerHeight);
            input.setSize(Math.max(100, width - 164), Short.MAX_VALUE);
            int editorHeight = Math.min(108, Math.max(24, input.getPreferredSize().height));
            int composeHeight = Math.max(60, editorHeight + 18); int composeY = getHeight() - composeHeight - 30;
            composer.setBounds(x, composeY, width, composeHeight);
            timeline.setBounds(x, top + headerHeight + 6, width, Math.max(50, composeY - top - headerHeight - 14));
            hint.setBounds(x + 8, getHeight() - 27, Math.max(150, width - 100), 18);
            counter.setBounds(x + width - 82, getHeight() - 27, 78, 18); counter.setHorizontalAlignment(SwingConstants.RIGHT);
        }
    }

    public static void main(String[] args) {
        if (args.length > 3) { System.err.println("用法：java -cp target/ltalk-1.0.0.jar cn.ltalk.client.ChatWindow [服务端IP] [端口] [用户标识]"); System.exit(1); }
        if (GraphicsEnvironment.isHeadless()) { System.err.println("桌面客户端需要图形桌面环境。"); System.exit(1); }
        SwingUtilities.invokeLater(() -> {
            ChatTheme.install();
            new ChatWindow(args.length > 0 ? args[0] : null, args.length > 1 ? args[1] : null, args.length > 2 ? args[2] : null).setVisible(true);
        });
    }
}
