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
import java.util.concurrent.Callable;

/** Swing presentation adapter; account and export operations stay in the background client. */
public final class ChatWindow extends JFrame implements ClientSessions.Listener {
    private final ConversationStore store = new ConversationStore();
    private final ClientSessions client = new ClientSessions(this);
    private final ConnectionPanel connection;
    private final ServerSelection serverSelection;
    private final AuthenticationPanel authentication;
    private final WelcomePanel welcome;
    private final JPanel pages = new JPanel(new CardLayout());
    private UserProfile profile;
    private JDialog profileDialog;
    private ProfilePanel profilePanel;
    private boolean profileSaving;
    private JDialog passwordDialog;
    private boolean closed;
    private boolean choosingExport;
    private long sessionRevision;
    private boolean historyLoading, deletingHistory;
    private int historyCount;
    private final Timer historyRender = new Timer(60, e -> refreshHistoryView());
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
    private final JLabel recoveryStatus = ChatTheme.label("连接已断开", 12, ChatTheme.MUTED, false);
    private final JButton reconnect = ChatTheme.pill("重新连接");
    private final JPanel recoveryBar = new JPanel(new BorderLayout(8, 0));
    private final JLabel recipient = ChatTheme.label("群聊 · 所有在线用户", 12, ChatTheme.MUTED, false);
    private final JLabel title = ChatTheme.label("公共聊天室", 17, ChatTheme.INK, true);
    private final JLabel headerAvatar = new JLabel();
    private final JLabel onlineTitle = ChatTheme.label("消息", 12, ChatTheme.MUTED, false);
    private final JLabel counter = ChatTheme.label("", 10, ChatTheme.MUTED, false);
    private final JLabel hint = ChatTheme.label("Enter 发送 · Shift+Enter 换行", 10, ChatTheme.MUTED, false);
    private final UserIdentityButton identity = new UserIdentityButton();
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
    private ChatClient.State connectionState = ChatClient.State.DISCONNECTED;
    private ClientSessions.Address signedInAddress;
    private boolean refreshingList, restoringDraft;
    private final AppearancePreferences appearancePreferences;
    private AppearanceSettings appearance;
    private final WallpaperPanel background;

    public ChatWindow(String initialHost, String initialPort, String initialUser) {
        this(initialHost, initialPort, initialUser, AppearancePreferences.defaultFile());
    }
    ChatWindow(String initialHost, String initialPort, String initialUser, Path appearanceFile) {
        this(initialHost, initialPort, initialUser, appearanceFile, DiscoveryClient::discover);
    }
    ChatWindow(String initialHost, String initialPort, String initialUser, Path appearanceFile,
               Callable<List<DiscoveryClient.Candidate>> discover) {
        super("LTalk · 局域网聊天室");
        appearancePreferences = new AppearancePreferences(appearanceFile); appearance = appearancePreferences.load();
        connection = new ConnectionPanel(initialHost, initialPort);
        serverSelection = new ServerSelection(connection, new RecentServers(appearanceFile.resolveSibling("recent-servers.properties")),
                discover, () -> !closed && !chatPageVisible() && client.authenticationState() == ChatClient.State.DISCONNECTED,
                this::connectServer);
        authentication = new AuthenticationPanel(initialUser);
        welcome = new WelcomePanel(connection, authentication);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(360, 540)); setSize(1200, 820); setLocationByPlatform(true);
        status.setName("status"); recipient.setName("recipient"); title.setName("conversationTitle");
        headerAvatar.setName("conversationAvatar");
        timeline.setContactProfiles(store::profile);
        timeline.onRecover(this::recoverMessage);
        send.setName("send"); group.setName("group"); back.setName("back"); menu.setName("menu");
        create.setName("createConversation"); attach.setName("attach"); emoji.setName("emoji"); input.setName("input");
        hint.setName("composerHint");
        chatSearch.setName("chatSearch"); more.setName("chatMore");
        buildSidebar();
        search.putClientProperty("placeholder", "搜索"); messageSearch.putClientProperty("placeholder", "搜索当前会话消息");
        authentication.user.putClientProperty("placeholder", "例如 alice");

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
        shell = new ResponsiveShell(sidebar, background);
        JScrollPane welcomeScroll = new JScrollPane(welcome); ChatTheme.scroll(welcomeScroll, false); welcomeScroll.setName("welcomeScroll");
        ChatTheme.surface(recoveryBar);
        recoveryBar.setBorder(new EmptyBorder(5, 12, 5, 12));
        recoveryStatus.setName("recoveryStatus");
        reconnect.setName("reconnect");
        recoveryBar.add(recoveryStatus, BorderLayout.CENTER);
        recoveryBar.add(reconnect, BorderLayout.EAST);
        recoveryBar.setVisible(false);
        JPanel chatPage = new JPanel(new BorderLayout());
        chatPage.add(recoveryBar, BorderLayout.NORTH);
        chatPage.add(shell, BorderLayout.CENTER);
        pages.add(welcomeScroll, "welcome"); pages.add(chatPage, "chat"); setContentPane(pages); showWelcome();
        shell.onLayout(() -> {
            back.setVisible(shell.compact());
            int before = store.selected().unread();
            store.setVisible(chatPageVisible() && background.isVisible() && (!isVisible() || isFocused()));
            if (before != store.selected().unread()) SwingUtilities.invokeLater(this::refreshList);
        });

        connection.connect.addActionListener(e -> connectServer()); connection.disconnect.addActionListener(e -> client.disconnect());
        connection.host.getDocument().addDocumentListener(changed(serverSelection::editedAddress));
        connection.port.getDocument().addDocumentListener(changed(serverSelection::editedAddress));
        reconnect.addActionListener(e -> reconnect());
        authentication.submit.addActionListener(e -> authenticate());
        authentication.password.addActionListener(e -> { if (authentication.submit.isEnabled()) authenticate(); });
        authentication.confirmation.addActionListener(e -> { if (authentication.submit.isEnabled()) authenticate(); });
        welcome.back.addActionListener(e -> showChat()); identity.addActionListener(e -> showIdentityMenu());
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
            @Override public void windowClosing(WindowEvent e) {
                closed = true; serverSelection.cancel(); historyRender.stop();
                store.saveDraft(input.getText()); closeProfile(); client.close(); dispose();
            }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) { store.setVisible(chatPageVisible() && (!shell.compact() || shell.chatOpen())); refreshList(); }
            @Override public void windowLostFocus(WindowEvent e) { store.setVisible(false); }
        });
        connection.state(true, false); authentication.state(false, false);
        historyRender.setRepeats(false);
        applyAppearance(appearance, false); refreshList(); refreshConversation();
        SwingUtilities.invokeLater(() -> {
            if (closed || client.state() != ChatClient.State.DISCONNECTED) return;
            if (initialHost != null && !initialHost.isBlank()) connectServer();
            else if (initialPort == null) serverSelection.startDiscovery();
        });
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
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4)); ChatTheme.surface(tabs);
        group.setFocusPainted(false); group.setBorder(new EmptyBorder(8, 14, 8, 14)); group.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        group.setToolTipText("选择公共群聊"); tabs.add(group); tabs.add(ChatTheme.label("全部会话", 11, ChatTheme.MUTED, false)); top.add(tabs);
        sidebar.add(top, BorderLayout.NORTH);
        JScrollPane listScroll = new JScrollPane(conversations); ChatTheme.scroll(listScroll, false);
        ChatTheme.surface(listCards); listCards.add(listScroll, "list");
        JPanel empty = new JPanel(new GridBagLayout()); ChatTheme.surface(empty); empty.add(ChatTheme.label("没有匹配的会话", 13, ChatTheme.MUTED, false)); listCards.add(empty, "empty");
        sidebar.add(listCards, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(4, 5)); ChatTheme.surface(footer); footer.setBorder(new EmptyBorder(11, 20, 13, 14));
        footer.add(identity, BorderLayout.CENTER); footer.add(status, BorderLayout.SOUTH); sidebar.add(footer, BorderLayout.SOUTH);
    }

    private void connectServer() {
        serverSelection.cancel();
        try { client.connectServer(connection.host.getText(), Integer.parseInt(connection.port.getText().strip())); }
        catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            String detail = ex instanceof NumberFormatException ? "端口必须是 1～65535 的整数" : ex.getMessage();
            showError(detail); connection.feedback(detail);
        }
    }
    private void reconnect() {
        if (signedInAddress == null || connectionState == ChatClient.State.CONNECTED) return;
        showWelcome();
        welcome.back.setVisible(true);
        if (connectionState != ChatClient.State.DISCONNECTED) {
            if (connectionState == ChatClient.State.SERVER_CONNECTED) authentication.password.requestFocusInWindow();
            return;
        }
        client.cancelSwitch();
        authentication.clearCredentials(false);
        authentication.switching(false);
        authentication.user.setText(store.self());
        try {
            connection.host.setText(signedInAddress.host());
            connection.port.setText(Integer.toString(signedInAddress.port()));
            client.connectServer(signedInAddress.host(), signedInAddress.port());
        } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            authentication.feedback(ex.getMessage(), true);
        }
    }

    private void authenticate() {
        try {
            String password = new String(authentication.password.getPassword());
            if (authentication.registering()) {
                if (!password.equals(new String(authentication.confirmation.getPassword()))) throw new IllegalArgumentException("两次密码不一致");
                client.registerAndLogin(authentication.user.getText(), authentication.nickname.getText(), password, authentication.random.isSelected());
            } else client.login(authentication.user.getText(), password);
        } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            if (client.switching()) authentication.feedback(ex.getMessage(), true);
            else showError(ex.getMessage());
        }
    }
    private void send() {
        if (!store.canSend()) { showError("请明确重新选择在线私聊对象或公共聊天室"); return; }
        ConversationId submittedTo = store.selectedId();
        try {
            String content = input.getText();
            String id = submittedTo.kind() == ConversationId.Kind.GROUP ? client.sendGroup(content) : client.sendPrivate(submittedTo.key(), content);
            store.submitted(submittedTo, id, content);
            input.setText(""); input.requestFocusInWindow(); renderTimeline();
            hint.setText("Enter 发送 · Shift+Enter 换行"); ChatTheme.foreground(hint, ThemePalette.Role.MUTED);
        } catch (IOException ex) { showError(ex.getMessage()); }
    }

    private void recoverMessage(ConversationStore.Entry entry) {
        ConversationId selected = store.selectedId();
        long revision = sessionRevision;
        if (!input.getText().isEmpty() && !input.getText().equals(entry.content())
                && JOptionPane.showConfirmDialog(this, "用取回的正文替换当前草稿？", "取回正文",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        if (revision != sessionRevision || !selected.equals(store.selectedId())) return;
        input.setText(entry.content());
        input.requestFocusInWindow();
        if (entry.sendStatus() == ConversationStore.SendStatus.UNCONFIRMED)
            feedback("正文已取回。原消息结果待确认，重新发送可能重复。", true);
    }

    private void selectConversation(ConversationId id) {
        store.saveDraft(input.getText()); store.select(id); shell.openChat();
        messageSearch.setText(""); refreshList(); refreshConversation(); input.requestFocusInWindow();
    }
    private void refreshConversationHeader() {
        ConversationStore.Summary selected = store.selected();
        boolean group = selected.id().kind() == ConversationId.Kind.GROUP;
        title.setText(selected.title());
        title.setToolTipText(group ? null : "@" + selected.id().key());
        headerAvatar.setIcon(group ? new Avatar(selected.title(), true, 42)
                : ProfileAvatar.contact(selected.profile(), selected.title(), 42));
        if (signedInAddress != null && connectionState != ChatClient.State.CONNECTED) {
            recipient.setText((group ? "群聊" : "私聊") + " · 暂未连接");
        } else {
            recipient.setText(store.targetLabel() + (group ? " · " + store.onlineCount() + " 人在线" : ""));
        }
        recipient.setToolTipText(group ? recipient.getText() : recipient.getText() + " · @" + selected.id().key());
    }
    private void refreshConversation() {
        refreshConversationHeader();
        restoringDraft = true;
        try { input.setText(store.draft()); } finally { restoringDraft = false; }
        send.setEnabled(connectionState == ChatClient.State.CONNECTED && store.canSend());
        input.setToolTipText(connectionState != ChatClient.State.CONNECTED ? "重新登录后可以继续发送"
                : store.canSend() ? "消息 · Enter 发送，Shift+Enter 换行" : "草稿已保留，请明确重新选择在线联系人或群聊");
        renderTimeline();
    }
    private void renderTimeline() {
        renderTimeline(false);
    }
    private void renderTimeline(boolean keepReadingPosition) {
        timeline.deletion(connectionState == ChatClient.State.CONNECTED && !historyLoading && !deletingHistory, this::deleteMessage);
        timeline.showEntries(store.entries(), store.self(), messageSearch.getText(), connectionState == ChatClient.State.CONNECTED, keepReadingPosition);
    }
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
        boolean wasLoggedIn = connectionState == ChatClient.State.CONNECTED;
        connectionState = state;
        boolean loggedIn = state == ChatClient.State.CONNECTED;
        if (loggedIn) {
            store.saveDraft(input.getText());
            boolean changedIdentity = !store.self().equals(profile.userId()) || !client.address().equals(signedInAddress);
            if (changedIdentity) store.useIdentity("");
            store.useIdentity(profile.userId());
            store.clearProfiles();
            signedInAddress = client.address();
            if (changedIdentity) { search.setText(""); messageSearch.setText(""); }
            restoreAddress(); authentication.switching(false);
            hint.setText("Enter 发送 · Shift+Enter 换行"); hint.setToolTipText(null); ChatTheme.foreground(hint, ThemePalette.Role.MUTED);
            authentication.clearCredentials(false); showChat();
        } else {
            if (state == ChatClient.State.DISCONNECTED) {
                sessionRevision++;
                store.disconnected();
                closeProfile();
                historyLoading = false; deletingHistory = false; historyRender.stop();
                if (signedInAddress != null) {
                    hint.setText("草稿可继续编辑");
                    hint.setToolTipText(null);
                    ChatTheme.foreground(hint, ThemePalette.Role.MUTED);
                }
                if (!client.switching()) {
                    authentication.password.setText(""); authentication.confirmation.setText(""); authentication.hidePasswords();
                }
                if (wasLoggedIn && !client.switching()) showChat();
            } else if (signedInAddress == null) showWelcome();
        }
        if (client.switching()) updateAuthentication(client.authenticationState(), "正在切换账号，原连接已断开");
        else updateAuthentication(state, detail);
        welcome.back.setVisible(signedInAddress != null);
        recoveryBar.setVisible(!loggedIn && signedInAddress != null);
        reconnect.setText(state == ChatClient.State.DISCONNECTED ? "重新连接" : "继续登录");
        recoveryStatus.setText(switch (state) {
            case DISCONNECTED -> "连接已断开";
            case CONNECTING -> "正在连接服务器…";
            case SERVER_CONNECTED -> "服务器已连接，请登录";
            case AUTHENTICATING -> "正在验证账号…";
            case CONNECTED -> "";
        });
        recoveryStatus.setToolTipText(detail);
        identity.showProfile(profile);
        setTitle("LTalk · 局域网聊天室" + (profile == null ? "" : " · " + profile.nickname()));
        setStatus(detail, false); refreshList(); refreshConversation();
        if (loggedIn) reloadHistory();
        else if (signedInAddress != null && !client.switching() && state == ChatClient.State.SERVER_CONNECTED && !chatPageVisible())
            authentication.password.requestFocusInWindow();
    }
    private void reloadHistory() {
        try { client.loadHistory(); } catch (IOException ex) { showError(ex.getMessage()); }
    }
    private void refreshHistoryView() { refreshList(); refreshConversationHeader(); renderTimeline(); }
    @Override public void onHistoryStarted() {
        historyLoading = true; historyCount = 0; store.beginHistory(); setStatus("正在加载本人聊天记录…", false); refreshHistoryView();
    }
    @Override public void onHistoryRecord(ChatRecord record, String nickname) {
        store.restore(record, nickname); historyCount++; if (!historyRender.isRunning()) historyRender.start();
    }
    @Override public void onHistoryComplete() {
        historyLoading = false; historyRender.stop(); refreshHistoryView(); setStatus("聊天记录已加载 · " + historyCount + " 条", false);
    }
    @Override public void onHistoryDeleted(String scope, String id) {
        deletingHistory = false; historyLoading = false; store.deleteHistory(scope, id); refreshHistoryView(); reloadHistory();
    }
    private String scope(ConversationId id) throws IOException {
        return id.kind() == ConversationId.Kind.GROUP ? ConversationScope.PUBLIC : ConversationScope.privateChat(store.self(), id.key());
    }
    private void deleteMessage(ConversationStore.Entry entry) {
        if (entry.messageId() == null) return;
        try {
            String target = entry.to() == null ? ConversationScope.PUBLIC : ConversationScope.privateChat(entry.from(), entry.to());
            confirmDelete(target, entry.messageId(), "删除这条记录？");
        } catch (IOException ex) { showError(ex.getMessage()); }
    }
    private void clearHistory() {
        try { confirmDelete(scope(store.selectedId()), null, "清空「" + store.selected().title() + "」的本人聊天记录？"); }
        catch (IOException ex) { showError(ex.getMessage()); }
    }
    private void confirmDelete(String scope, String id, String prompt) {
        if (historyLoading || deletingHistory || connectionState != ChatClient.State.CONNECTED) return;
        long revision = sessionRevision; String user = store.self();
        if (JOptionPane.showConfirmDialog(this, prompt + "\n只删除本人的副本，不影响对方或其他成员。删除后无法恢复。", "确认删除聊天记录", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        if (revision != sessionRevision || !user.equals(store.self()) || connectionState != ChatClient.State.CONNECTED) return;
        try { client.deleteHistory(scope, id); deletingHistory = true; renderTimeline(); setStatus("正在删除本人记录…", false); }
        catch (IOException ex) { showError(ex.getMessage()); }
    }
    private void updateAuthentication(ChatClient.State state, String detail) {
        if (state == ChatClient.State.SERVER_CONNECTED) serverSelection.remember(client.authenticationAddress());
        connection.state(state == ChatClient.State.DISCONNECTED, state == ChatClient.State.CONNECTING);
        if (state == ChatClient.State.DISCONNECTED) connection.feedback(detail);
        authentication.state(state == ChatClient.State.SERVER_CONNECTED, state == ChatClient.State.AUTHENTICATING);
        authentication.feedback(detail, false);
    }
    @Override public void onSwitchState(ChatClient.State state, String detail) {
        updateAuthentication(state, connectionState == ChatClient.State.CONNECTED ? "原账号保持在线；" + detail : detail);
        welcome.back.setVisible(signedInAddress != null);
    }
    @Override public void onSwitchError(Message message) { authentication.feedback(message.code() + "：" + message.message(), true); }
    @Override public void onSwitchRegistered(String userId) { onRegistered(userId); }
    @Override public void onSessionReplaced() {
        historyLoading = false; deletingHistory = false; historyRender.stop();
        sessionRevision++; closeProfile(); store.useIdentity(""); input.setText(""); search.setText(""); messageSearch.setText("");
    }
    @Override public void onProfile(UserProfile value) {
        profile = value;
        if (connectionState == ChatClient.State.CONNECTED) {
            identity.showProfile(value); setTitle("LTalk · 局域网聊天室 · " + value.nickname());
            if (profileSaving) { closeProfile(); setStatus("个人资料已保存", false); }
        }
    }
    @Override public void onDisplayProfile(ContactProfile value) {
        if (!store.updateProfile(value)) return;
        if (historyLoading) {
            if (!historyRender.isRunning()) historyRender.start();
        } else {
            refreshList(); refreshConversationHeader(); renderTimeline(true);
        }
    }
    @Override public void onGroup(Message message) { receiveChat(message); }
    @Override public void onPrivate(Message message) { receiveChat(message); }
    private void receiveChat(Message message) {
        ConversationId destination = store.receive(message); refreshList();
        if (destination.equals(store.selectedId())) renderTimeline(true);
    }
    @Override public void onChatSendError(Message message, boolean uncertain) {
        ConversationId target = store.sendError(message.requestId(), uncertain, message.message());
        if (target != null && target.equals(store.selectedId())) renderTimeline(true);
    }
    @Override public void onUserList(Message message) {
        if (store.updateUsers(message.users())) store.notice(store.selectedId(), "私聊对象已离线，草稿保留；请重新选择会话。", false);
        refreshList(); refreshConversation();
    }
    @Override public void onSystem(Message message) {
        store.system(message.content()); if (store.selectedId().equals(ConversationId.PUBLIC)) renderTimeline(true);
    }
    @Override public void onError(Message message) {
        if (message.code() != null && message.code().startsWith("HISTORY_")) { historyLoading = false; deletingHistory = false; historyRender.stop(); }
        String detail = message.code() + "：" + message.message();
        authentication.feedback(detail, true);
        if (profilePanel != null && profileSaving && message.requestId() != null && message.requestId().startsWith("profile-")) {
            profileSaving = false; profilePanel.failed(detail);
        }
        ConversationId target = store.error(message); feedback(detail, true);
        if (target.equals(store.selectedId())) renderTimeline();
    }
    private void showError(String detail) { authentication.feedback(detail, true); store.notice(store.selectedId(), detail, true); feedback(detail, true); renderTimeline(); }
    private void feedback(String detail, boolean error) {
        setStatus(detail, error); hint.setText(detail); hint.setToolTipText(detail);
        ChatTheme.foreground(hint, error ? ThemePalette.Role.DANGER : ThemePalette.Role.MUTED);
    }
    private void setStatus(String detail, boolean error) {
        status.setText(detail); status.setToolTipText(detail); ChatTheme.foreground(status, error ? ThemePalette.Role.DANGER : ThemePalette.Role.MUTED);
    }

    private void showWelcome() { ((CardLayout) pages.getLayout()).show(pages, "welcome"); store.setVisible(false); }
    private boolean chatPageVisible() { return shell.getParent().isVisible(); }
    private void showChat() {
        if (signedInAddress == null) return;
        serverSelection.cancel();
        if (client.switching()) {
            client.cancelSwitch(); restoreAddress(); authentication.clearCredentials(true); authentication.switching(false);
            updateAuthentication(connectionState, "已返回原账号聊天");
        }
        ((CardLayout) pages.getLayout()).show(pages, "chat");
        store.setVisible(!shell.compact() || shell.chatOpen()); pages.revalidate(); pages.repaint();
        refreshList(); refreshConversation();
    }
    private void restoreAddress() {
        ClientSessions.Address address = client.address();
        if (address != null) { connection.host.setText(address.host()); connection.port.setText(Integer.toString(address.port())); }
    }
    private void toggleConnection() { showWelcome(); }
    @Override public void onRegistered(String userId) {
        authentication.user.setText(userId); authentication.feedback("注册成功，正在登录…", false);
    }
    ChatPopupMenu identityMenu() {
        ChatPopupMenu popup = new ChatPopupMenu(identity, profile == null ? "未登录" : profile.nickname());
        if (connectionState != ChatClient.State.CONNECTED) {
            popup.action("accountLogin", "登录账号", LineIcon.Kind.USERS, this::showWelcome);
            if (signedInAddress != null) popup.action("accountLogout", "退出登录", LineIcon.Kind.LOGOUT, this::logout);
        }
        else {
            popup.action("accountProfile", "个人信息", LineIcon.Kind.USERS, this::showProfile);
            popup.action("accountLogout", "退出登录", LineIcon.Kind.LOGOUT, this::logout);
            popup.action("accountSwitch", "切换账号", LineIcon.Kind.USERS, this::switchAccount);
        }
        return popup;
    }
    private void showIdentityMenu() { ChatPopupMenu popup = identityMenu(); popup.show(identity, 0, -popup.getPreferredSize().height - 4); }
    private void switchAccount() {
        try {
            store.saveDraft(input.getText()); closeProfile();
            client.beginSwitch(); authentication.clearCredentials(true); authentication.switching(true);
            showWelcome(); welcome.back.setVisible(true); updateAuthentication(client.authenticationState(), "原账号保持在线，请登录新账号");
        } catch (IOException | RuntimeException ex) { showError(ex.getMessage()); }
    }
    private void logout() {
        client.cancelSwitch();
        client.disconnect(); connectionState = ChatClient.State.DISCONNECTED; signedInAddress = null;
        closeProfile(); profile = null; identity.showProfile(null);
        store.useIdentity(""); input.setText(""); search.setText(""); messageSearch.setText("");
        authentication.clearCredentials(false); authentication.switching(false); showWelcome(); refreshList(); refreshConversation();
        // Re-establish only the unauthenticated transport after an explicit account action.
        connectServer();
    }
    private void showProfile() {
        if (profile == null || connectionState != ChatClient.State.CONNECTED) return;
        closeProfile();
        JDialog dialog = new JDialog(this, "个人信息", Dialog.ModalityType.MODELESS); profileDialog = dialog; dialog.setName("profileDialog");
        profilePanel = new ProfilePanel(profile, connection.host.getText() + ":" + connection.port.getText(), (nickname, avatar) -> {
            try { profileSaving = true; client.updateProfile(nickname, avatar); }
            catch (IOException ex) { profileSaving = false; profilePanel.failed(ex.getMessage()); }
        }, this::closeProfile, this::showPasswordChange);
        ChatTheme.apply(profilePanel, ThemePalette.of(appearance));
        JScrollPane scroll = new JScrollPane(profilePanel); ChatTheme.scroll(scroll, false); dialog.setContentPane(scroll);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent e) { if (profileDialog == dialog) closeProfile(); } });
        dialog.pack(); Rectangle screen = getGraphicsConfiguration().getBounds();
        dialog.setSize(Math.min(dialog.getWidth() + 12, screen.width - 48), Math.min(dialog.getHeight() + 12, screen.height - 80));
        dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }
    private void closeProfile() {
        JDialog dialog = profileDialog; profileDialog = null;
        if (profilePanel != null) profilePanel.close(); profilePanel = null; profileSaving = false;
        if (passwordDialog != null) passwordDialog.dispose();
        if (dialog != null) dialog.dispose();
    }
    private void showPasswordChange() {
        if (profileDialog == null || connectionState != ChatClient.State.CONNECTED) return;
        if (passwordDialog != null && passwordDialog.isDisplayable()) {
            passwordDialog.toFront();
            return;
        }
        JDialog dialog = new JDialog(profileDialog, "修改密码", Dialog.ModalityType.MODELESS);
        passwordDialog = dialog;
        dialog.setName("passwordChangeDialog");
        PasswordChangePanel panel = new PasswordChangePanel((current, next, onFailure) ->
                client.changePassword(current, next, response -> {
                    if (response.type() == MessageType.PASSWORD_CHANGED) {
                        setStatus("密码已修改，下次登录请使用新密码", false);
                        dialog.dispose();
                    } else if (dialog.isDisplayable()) {
                        onFailure.accept(response.message());
                    }
                }), dialog::dispose);
        JScrollPane scroll = new JScrollPane(panel);
        ChatTheme.scroll(scroll, false);
        ChatTheme.apply(scroll, ThemePalette.of(appearance));
        dialog.setContentPane(scroll);
        dialog.getRootPane().setDefaultButton(panel.submitButton());
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                panel.clear();
                if (passwordDialog == dialog) passwordDialog = null;
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(profileDialog);
        dialog.setVisible(true);
    }
    @Override public void onExported(Path path) { setStatus("导出成功：" + path, false); }
    private void exportHistory() {
        if (choosingExport) return;
        String exportingUser = store.self();
        long exportingSession = sessionRevision;
        ConversationId selected = store.selectedId();
        final String scope;
        try { scope = selected.kind() == ConversationId.Kind.GROUP ? cn.ltalk.common.ConversationScope.PUBLIC
                : cn.ltalk.common.ConversationScope.privateChat(store.self(), selected.key()); }
        catch (IOException ex) { showError(ex.getMessage()); return; }
        final String suggested;
        try { suggested = HistoryExport.defaultName(exportingUser, "json"); } catch (IOException ex) { showError(ex.getMessage()); return; }
        choosingExport = true;
        new FileDialogs().saveExport(this, suggested, choice -> {
            choosingExport = false;
            if (closed || choice == null) return;
            if (exportingSession != sessionRevision || !exportingUser.equals(store.self()) || connectionState != ChatClient.State.CONNECTED) {
                showError("登录身份已改变，请重新选择会话导出"); return;
            }
            try { client.exportTo(choice.path(), choice.format(), scope, choice.replace()); }
            catch (IOException ex) { showError(ex.getMessage()); }
        }, detail -> { choosingExport = false; if (!closed) showError(detail); });
    }
    ChatPopupMenu mainMenu() {
        ChatPopupMenu popup = new ChatPopupMenu(menu, "LTalk");
        popup.action("menuConnection", "连接设置", LineIcon.Kind.SETTINGS, this::toggleConnection);
        popup.action("menuAppearance", "外观设置", LineIcon.Kind.PALETTE, this::showAppearance);
        popup.action("menuMode", appearance.dark() ? "切换浅色模式" : "切换深色模式", appearance.dark() ? LineIcon.Kind.SUN : LineIcon.Kind.MOON,
                () -> applyAppearance(appearance.withMode(appearance.dark() ? AppearanceSettings.Mode.LIGHT : AppearanceSettings.Mode.DARK), true));
        popup.addSeparator();
        JMenuItem disconnect = popup.action("menuDisconnect", "断开连接", LineIcon.Kind.LOGOUT, client::disconnect);
        disconnect.setEnabled(connectionState != ChatClient.State.DISCONNECTED);
        popup.action("menuAbout", "关于 LTalk", LineIcon.Kind.USERS, () -> setStatus("LTalk · 局域网聊天，群聊与私聊由服务端转发", false));
        return popup;
    }
    private void showMenu() { ChatPopupMenu popup = mainMenu(); popup.show(menu, 0, menu.getHeight() + 4); }
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
    ChatPopupMenu conversationMenu() {
        ChatPopupMenu popup = new ChatPopupMenu(more, store.selected().title());
        popup.action("chatLatest", "回到最新消息", LineIcon.Kind.BACK, timeline::scrollToBottom);
        JMenuItem export = popup.action("chatExport", "导出聊天记录", LineIcon.Kind.SETTINGS, this::exportHistory);
        export.setEnabled(connectionState == ChatClient.State.CONNECTED);
        JMenuItem clear = popup.action("chatClearHistory", "清空当前会话记录（仅本人）", LineIcon.Kind.CLOSE, this::clearHistory);
        clear.setEnabled(connectionState == ChatClient.State.CONNECTED && !historyLoading && !deletingHistory);
        JMenuItem reload = popup.action("chatReloadHistory", "重新加载聊天记录", LineIcon.Kind.BACK, this::reloadHistory);
        reload.setEnabled(connectionState == ChatClient.State.CONNECTED && !deletingHistory);
        return popup;
    }
    private void showChatMenu() { ChatPopupMenu popup = conversationMenu(); popup.show(more, more.getWidth() - popup.getPreferredSize().width, more.getHeight() + 4); }
    private void showAppearance() {
        JDialog dialog = new JDialog(this, "外观设置", Dialog.ModalityType.APPLICATION_MODAL); dialog.setName("appearanceDialog");
        AppearancePanel panel = new AppearancePanel(appearance, value -> { applyAppearance(value, true); dialog.dispose(); }, dialog::dispose);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); dialog.setContentPane(panel); dialog.pack();
        dialog.setResizable(true); dialog.setLocationRelativeTo(this);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setVisible(true);
    }
    void applyAppearance(AppearanceSettings value, boolean persist) {
        appearance = value; ThemePalette palette = ThemePalette.of(value); background.setAppearance(value);
        ChatTheme.apply(pages, palette); sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, palette.line()));
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
