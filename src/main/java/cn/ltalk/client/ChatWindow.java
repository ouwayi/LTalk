package cn.ltalk.client;

import cn.ltalk.client.ui.*;
import cn.ltalk.common.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Swing presentation adapter; account and export operations stay in the background client. */
public final class ChatWindow extends JFrame implements ClientSessions.Listener {
    private static final String DELETE_ALL_HISTORY = "删除所有聊天记录";

    @FunctionalInterface
    interface UploadPicker {
        void choose(Component owner, FileDialogs.Kind kind, Consumer<Path> completed, Consumer<String> failed);
    }

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
    private boolean choosingAttachment;
    private JDialog transferDialog;
    private AttachmentChannel.Handle transfer;
    private long sessionRevision;
    private boolean deletingHistory;
    private long historyRevision;
    private final Map<ConversationId, HistoryPosition> historyPositions = new HashMap<>();
    private HistorySearchDialog historySearch;
    private List<ConversationStore.Entry> historyContext;
    private long locationRevision;
    private ConversationStore.Entry unreadAnchor, unreadLookup;
    private SocialDialog socialDialog;
    private List<SocialItem> socialItems = List.of();
    private boolean socialLoading, socialDirty, privateRequesting;
    private boolean contactsTab, infoRefreshQueued;
    private String infoUser;
    private GroupRoster groupRoster;
    private GroupTransferDialog groupTransferDialog;
    private final JTextField search = ChatTheme.field("", "conversationSearch");
    private final ComposerInput input = new ComposerInput();
    private final JLabel status = ChatTheme.label("未连接 · 点击连接聊天室开始", 11, ChatTheme.MUTED, false);
    private final JLabel recoveryStatus = ChatTheme.label("连接已断开", 12, ChatTheme.MUTED, false);
    private final JButton reconnect = ChatTheme.pill("重新连接");
    private final JPanel recoveryBar = new JPanel(new BorderLayout(8, 0));
    private final JLabel recipient = ChatTheme.label("群聊 · 所有在线用户", 12, ChatTheme.MUTED, false);
    private final JLabel title = ChatTheme.label("公共聊天室", 17, ChatTheme.INK, true);
    private final JLabel headerAvatar = new JLabel();
    private final JButton conversationIdentity = ChatTheme.iconButton(LineIcon.Kind.USERS, "查看会话信息");
    private final JLabel onlineTitle = ChatTheme.label("消息", 12, ChatTheme.MUTED, false);
    private final JLabel counter = ChatTheme.label("", 10, ChatTheme.MUTED, false);
    private final JLabel hint = ChatTheme.label("Enter 发送 · Shift+Enter 换行", 10, ChatTheme.MUTED, false);
    private final UserIdentityButton identity = new UserIdentityButton();
    private final JButton send = ChatTheme.primary("");
    private final JToggleButton group = ChatTheme.choice("公共聊天室");
    private final JToggleButton contacts = ChatTheme.choice("好友与群聊");
    private static final String OTHER_CONVERSATIONS = "列表外的会话";
    private final JButton requestInbox = ChatTheme.pill("通知");
    private final JButton back = ChatTheme.iconButton(LineIcon.Kind.BACK, "返回会话列表");
    private final JButton menu = ChatTheme.iconButton(LineIcon.Kind.MENU, "菜单与连接设置");
    private final JButton create = ChatTheme.iconButton(LineIcon.Kind.PLUS, "更多操作");
    private final JButton chatSearch = ChatTheme.iconButton(LineIcon.Kind.SEARCH, "搜索当前会话消息");
    private final JButton more = ChatTheme.iconButton(LineIcon.Kind.MORE, "会话更多操作");
    private final JButton attach = ChatTheme.iconButton(LineIcon.Kind.ATTACH, "发送附件");
    private final JButton image = ChatTheme.iconButton(LineIcon.Kind.IMAGE, "发送图片");
    private final JButton emoji = ChatTheme.iconButton(LineIcon.Kind.EMOJI, "选择 Emoji");
    private final DefaultListModel<ConversationStore.Summary> listModel = new DefaultListModel<>();
    private final ConversationList conversations = new ConversationList(listModel);
    private final MessageTimeline timeline = new MessageTimeline();
    private final JPanel listCards = new JPanel(new CardLayout());
    private final JPanel historyTools = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
    private final JButton olderHistory = ChatTheme.pill("加载更早消息");
    private final JButton returnLatest = ChatTheme.primary("");
    private final JButton newMessages = ChatTheme.pill("新消息");
    private final JButton requestPrivate = ChatTheme.pill("申请私聊");
    private final JPanel sidebar = new JPanel(new BorderLayout());
    private final ChatContent chatContent = new ChatContent();
    private final ConversationInfoPanel info = new ConversationInfoPanel(this::closeInfo, this::showGroupInfo, this::showUserInfo,
            this::copyInfoIdentifier);
    private final JPanel chatArea = new JPanel(null) {
        @Override public void doLayout() { layoutChatArea(); }
    };
    private final JPanel quoteDraft = new JPanel(new BorderLayout(8, 0));
    private final JLabel quoteDraftText = ChatTheme.label("", 12, ChatTheme.MUTED, false);
    private final ResponsiveShell shell;
    private final JScrollPane editorScroll;
    private final JPanel mediaActions = new JPanel(new GridLayout(1, 2));
    private final JPanel composeActions = new JPanel(new BorderLayout(4, 0));
    private final ChatTheme.RoundedPanel header;
    private final ChatTheme.RoundedPanel composer;
    private ChatClient.State connectionState = ChatClient.State.DISCONNECTED;
    private ClientSessions.Address signedInAddress;
    private boolean refreshingList, restoringDraft;
    private final AppearancePreferences appearancePreferences;
    private final UploadPicker uploadPicker;
    private final Supplier<Clipboard> clipboard;
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
        this(initialHost, initialPort, initialUser, appearanceFile, discover, new FileDialogs()::choose);
    }
    ChatWindow(String initialHost, String initialPort, String initialUser, Path appearanceFile,
               Callable<List<DiscoveryClient.Candidate>> discover, UploadPicker uploadPicker) {
        this(initialHost, initialPort, initialUser, appearanceFile, discover, uploadPicker,
                () -> Toolkit.getDefaultToolkit().getSystemClipboard());
    }
    ChatWindow(String initialHost, String initialPort, String initialUser, Path appearanceFile,
               Callable<List<DiscoveryClient.Candidate>> discover, UploadPicker uploadPicker, Supplier<Clipboard> clipboard) {
        super("LTalk · 局域网聊天室");
        this.uploadPicker = uploadPicker;
        this.clipboard = clipboard;
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
        timeline.messageActions(this::startReply, this::copyBody, this::locateOriginal);
        timeline.onRecall(this::recallMessage);
        timeline.attachmentActions(this::receiveAttachment);
        send.setName("send"); group.setName("group"); back.setName("back"); menu.setName("menu");
        create.setName("createConversation"); attach.setName("attach"); emoji.setName("emoji"); input.setName("input");
        image.setName("sendImage");
        hint.setName("composerHint");
        chatSearch.setName("chatSearch"); more.setName("chatMore");
        buildSidebar();
        search.putClientProperty("placeholder", "搜索");
        authentication.user.putClientProperty("placeholder", "例如 alice");

        header = new ChatTheme.RoundedPanel(new BorderLayout(8, 0), 24, Color.WHITE, true);
        header.setBorder(new EmptyBorder(10, 10, 10, 12));
        JPanel contact = new JPanel(new BorderLayout(10, 0)); contact.setOpaque(false);
        contact.add(back, BorderLayout.WEST);
        conversationIdentity.setName("conversationIdentity");
        conversationIdentity.setPreferredSize(null);
        conversationIdentity.setIcon(null);
        conversationIdentity.setBorder(new EmptyBorder(0, 0, 0, 6));
        conversationIdentity.setLayout(new BorderLayout(10, 0));
        conversationIdentity.add(headerAvatar, BorderLayout.WEST);
        JPanel names = new JPanel(new GridLayout(2, 1, 0, 1)); names.setOpaque(false); names.add(title); names.add(recipient);
        conversationIdentity.add(names, BorderLayout.CENTER);
        conversationIdentity.addActionListener(e -> openConversationInfo());
        contact.add(conversationIdentity, BorderLayout.CENTER); header.add(contact, BorderLayout.CENTER);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0)); tools.setOpaque(false); tools.add(chatSearch); tools.add(more);
        header.add(tools, BorderLayout.EAST);
        historyTools.setOpaque(false);
        olderHistory.setName("olderHistory");
        returnLatest.setName("returnLatest");
        returnLatest.setIcon(new LineIcon(LineIcon.Kind.DOWN, Color.WHITE, 23));
        returnLatest.setToolTipText("回到最新消息");
        returnLatest.getAccessibleContext().setAccessibleName("回到最新消息");
        returnLatest.setVisible(false);
        newMessages.setName("newMessages");
        requestPrivate.setName("requestPrivate");
        JLabel contextLabel = ChatTheme.label("消息定位", 12, ChatTheme.MUTED, false);
        historyTools.add(olderHistory);
        historyTools.add(requestPrivate);
        historyTools.add(newMessages);
        historyTools.add(contextLabel);
        header.add(historyTools, BorderLayout.SOUTH);
        historyTools.setVisible(false);
        olderHistory.addActionListener(e -> loadConversation(true));
        returnLatest.addActionListener(e -> { showLatest(); input.requestFocusInWindow(); });
        newMessages.addActionListener(e -> locateUnread());
        requestPrivate.addActionListener(e -> requestPrivate());
        timeline.addMouseWheelListener(e -> {
            if (e.getWheelRotation() < 0) SwingUtilities.invokeLater(this::loadAtTop);
            else if (e.getWheelRotation() > 0) SwingUtilities.invokeLater(this::readIfAtLatest);
        });
        timeline.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (e.getValueIsAdjusting() && e.getValue() == 0) loadAtTop();
            if (e.getValueIsAdjusting()) readIfAtLatest();
        });
        timeline.getViewport().addChangeListener(e -> updateLatestButton());

        input.setToolTipText("输入消息；Enter 发送，Shift+Enter 换行");
        editorScroll = new JScrollPane(input); ChatTheme.scroll(editorScroll, true);
        JPanel centeredEditor = new JPanel(new GridBagLayout());
        centeredEditor.setOpaque(false);
        GridBagConstraints editorLayout = new GridBagConstraints();
        editorLayout.weightx = 1;
        editorLayout.fill = GridBagConstraints.HORIZONTAL;
        centeredEditor.add(editorScroll, editorLayout);
        composer = new ChatTheme.RoundedPanel(new BorderLayout(8, 0), 26, Color.WHITE, true);
        mediaActions.setOpaque(false);
        mediaActions.add(attach); mediaActions.add(image);
        composer.setBorder(new EmptyBorder(8, 8, 8, 8));
        composer.add(mediaActions, BorderLayout.WEST); composer.add(centeredEditor, BorderLayout.CENTER);
        quoteDraft.setOpaque(false);
        quoteDraft.setBorder(new EmptyBorder(0, 8, 4, 0));
        quoteDraftText.setName("quoteDraftText");
        JButton cancelQuote = ChatTheme.iconButton(LineIcon.Kind.CLOSE, "取消引用");
        cancelQuote.setName("cancelQuote");
        cancelQuote.setPreferredSize(new Dimension(32, 32));
        cancelQuote.addActionListener(e -> { store.replyDraft(null); refreshQuoteDraft(); input.requestFocusInWindow(); });
        quoteDraft.add(quoteDraftText, BorderLayout.CENTER);
        quoteDraft.add(cancelQuote, BorderLayout.EAST);
        quoteDraft.setVisible(false);
        composer.add(quoteDraft, BorderLayout.NORTH);
        composeActions.setOpaque(false);
        composeActions.add(emoji, BorderLayout.WEST); composeActions.add(send, BorderLayout.EAST);
        send.setIcon(new LineIcon(LineIcon.Kind.SEND, Color.WHITE, 23)); send.setPreferredSize(new Dimension(44, 44));
        send.setToolTipText("发送消息"); send.getAccessibleContext().setAccessibleName("发送消息");
        composer.add(composeActions, BorderLayout.EAST);
        chatContent.add(returnLatest); chatContent.add(header); chatContent.add(timeline); chatContent.add(composer); chatContent.add(hint); chatContent.add(counter);
        chatContent.setName("chatContent");
        info.setVisible(false);
        chatArea.setOpaque(false);
        chatArea.add(chatContent);
        chatArea.add(info);
        background = new WallpaperPanel(new BorderLayout(), appearance); background.setName("chatPane"); background.add(chatArea);
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
            store.setVisible(readingConversation());
        });

        connection.connect.addActionListener(e -> connectServer()); connection.disconnect.addActionListener(e -> client.disconnect());
        connection.host.getDocument().addDocumentListener(changed(serverSelection::editedAddress));
        connection.port.getDocument().addDocumentListener(changed(serverSelection::editedAddress));
        connection.tls.addItemListener(e -> serverSelection.editedAddress());
        reconnect.addActionListener(e -> reconnect());
        authentication.submit.addActionListener(e -> authenticate());
        authentication.password.addActionListener(e -> { if (authentication.submit.isEnabled()) authenticate(); });
        authentication.confirmation.addActionListener(e -> { if (authentication.submit.isEnabled()) authenticate(); });
        welcome.back.addActionListener(e -> showChat()); identity.addActionListener(e -> showIdentityMenu());
        send.addActionListener(e -> send()); group.addActionListener(e -> chooseSidebarTab(false));
        contacts.addActionListener(e -> chooseSidebarTab(true));
        back.addActionListener(e -> { closeInfo(); shell.openList(); refreshList(); });
        menu.addActionListener(e -> showMenu()); create.addActionListener(e -> showNewConversation()); more.addActionListener(e -> showChatMenu());
        chatSearch.addActionListener(e -> openHistorySearch());
        attach.addActionListener(e -> chooseAttachment(Attachment.Kind.FILE));
        image.addActionListener(e -> chooseAttachment(Attachment.Kind.IMAGE));
        emoji.addActionListener(e -> showEmojiPicker());
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
                closed = true; serverSelection.cancel(); closeHistorySearch(); closeSocialDialog();
                store.saveDraft(input.getText()); closeProfile(); closeTransfer(); client.close(); dispose();
            }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent e) {
                store.setVisible(readingConversation());
                if (historyContext == null) locateUnread();
            }
            @Override public void windowLostFocus(WindowEvent e) { store.setVisible(false); }
        });
        connection.state(true, false); authentication.state(false, false);
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
        ButtonGroup categories = new ButtonGroup();
        contacts.setName("contactsTab");
        for (JToggleButton tab : List.of(group, contacts)) {
            tab.setFont(ChatTheme.FONT.deriveFont(Font.BOLD, 13f));
            tab.setBorder(new EmptyBorder(8, 12, 8, 12));
            categories.add(tab);
            tabs.add(tab);
        }
        group.setSelected(true);
        requestInbox.setName("requestInbox");
        requestInbox.setVisible(false);
        requestInbox.addActionListener(e -> openSocial(SocialDialog.View.REQUESTS));
        tabs.add(requestInbox);
        top.add(tabs);
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
        try { client.connectServer(connection.host.getText(), Integer.parseInt(connection.port.getText().strip()), connection.tls.isSelected()); }
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
            connection.tls.setSelected(signedInAddress.tls());
            client.connectServer(signedInAddress.host(), signedInAddress.port(), signedInAddress.tls());
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
            String quote = store.replyDraft();
            String id = submittedTo.kind() == ConversationId.Kind.GROUP
                    ? client.sendGroup(submittedTo.equals(ConversationId.PUBLIC) ? null : scope(submittedTo), content, quote)
                    : client.sendPrivate(submittedTo.key(), content, quote);
            store.submitted(submittedTo, id, content, quote);
            store.replyDraft(null);
            input.setText(""); input.requestFocusInWindow(); showLatest();
            hint.setText("Enter 发送 · Shift+Enter 换行"); ChatTheme.foreground(hint, ThemePalette.Role.MUTED);
        } catch (IOException ex) { showError(ex.getMessage()); }
    }

    private void showEmojiPicker() {
        EmojiPicker picker = emojiPicker();
        picker.show(emoji, emoji.getWidth() - picker.getPreferredSize().width, -picker.getPreferredSize().height);
    }

    EmojiPicker emojiPicker() {
        long revision = sessionRevision;
        ConversationId selected = store.selectedId();
        return new EmojiPicker(ThemePalette.of(appearance), value -> {
            if (revision != sessionRevision || !selected.equals(store.selectedId())) return;
            input.replaceSelection(value);
            input.requestFocusInWindow();
        });
    }

    private void chooseAttachment(Attachment.Kind kind) {
        if (!send.isEnabled() || !canStartAttachment()) return;
        long revision = sessionRevision;
        ConversationStore.Summary destination = store.selected();
        choosingAttachment = true;
        FileDialogs.Kind picker = kind == Attachment.Kind.IMAGE ? FileDialogs.Kind.CHAT_IMAGE : FileDialogs.Kind.FILE;
        uploadPicker.choose(this, picker, path -> {
            try {
                if (path == null || !attachmentSession(revision)) return;
                confirmUpload(kind, destination, path, revision);
            } finally {
                choosingAttachment = false;
            }
        }, detail -> {
            choosingAttachment = false;
            if (attachmentSession(revision)) feedback(detail, true);
        });
    }

    private void confirmUpload(Attachment.Kind kind, ConversationStore.Summary destination, Path path, long revision) {
        ConversationId selected = destination.id();
        String target = selected.kind() == ConversationId.Kind.PRIVATE ? selected.key() : null;
        String recipientLabel = target == null ? destination.title() : destination.title() + " (@" + target + ")";
        String filename = Attachment.safeName(path.getFileName().toString());
        Object[] options = {"发送", "取消"};
        int confirmed = JOptionPane.showOptionDialog(this, "发送到：" + recipientLabel + "\n文件：" + filename,
                "确认发送", JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[1]);
        if (confirmed != 0 || !attachmentSession(revision)) return;
        String groupScope = target != null ? null
                : selected.equals(ConversationId.PUBLIC) ? ConversationScope.PUBLIC : ConversationScope.group(selected.key());
        startTransfer(kind == Attachment.Kind.IMAGE ? "发送图片" : "发送附件",
                AttachmentChannel.Request.upload(groupScope, target, path, kind));
    }

    private void receiveAttachment(ConversationStore.Entry entry, boolean preview) {
        ConversationStore.Entry current = shownMessage(entry.messageId());
        if (!attachmentSession(sessionRevision) || current == null || current.attachment() == null || !canStartAttachment()) return;
        String selectedScope;
        try { selectedScope = scope(store.selectedId()); }
        catch (IOException ex) { feedback(ex.getMessage(), true); return; }
        Attachment attachment = entry.attachment();
        if (preview) {
            startTransfer("加载原图", AttachmentChannel.Request.download(selectedScope, entry.messageId(), attachment, null, false));
            return;
        }
        long revision = sessionRevision;
        choosingAttachment = true;
        new FileDialogs().saveAttachment(this, Attachment.safeName(attachment.name()), choice -> {
            choosingAttachment = false;
            if (choice == null || !attachmentSession(revision)) return;
            startTransfer("保存附件", AttachmentChannel.Request.download(selectedScope, entry.messageId(), attachment,
                    choice.path(), choice.replace()));
        }, detail -> {
            choosingAttachment = false;
            if (attachmentSession(revision)) feedback(detail, true);
        });
    }

    private boolean canStartAttachment() {
        if (transfer != null || choosingAttachment) {
            feedback("请先完成当前文件操作", false);
            return false;
        }
        return true;
    }

    private boolean attachmentSession(long revision) {
        return !closed && revision == sessionRevision && connectionState == ChatClient.State.CONNECTED;
    }

    private void startTransfer(String title, AttachmentChannel.Request request) {
        long revision = sessionRevision;
        JDialog dialog = new JDialog(this, title, Dialog.ModalityType.MODELESS);
        dialog.setName("attachmentTransfer");
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));
        ChatTheme.surface(panel);
        JProgressBar progress = new JProgressBar(0, 100);
        progress.setPreferredSize(new Dimension(235, 23));
        progress.setStringPainted(true);
        progress.setString("正在准备…");
        progress.setIndeterminate(true);
        JButton cancel = ChatTheme.pill("取消");
        Runnable stop = () -> {
            cancel.setEnabled(false);
            progress.setString("正在取消…");
            if (transferDialog == dialog && transfer != null) transfer.cancel();
        };
        cancel.addActionListener(e -> stop.run());
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { stop.run(); }
        });
        dialog.getRootPane().registerKeyboardAction(e -> stop.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        panel.add(progress, BorderLayout.CENTER);
        panel.add(cancel, BorderLayout.EAST);
        ChatTheme.apply(panel, ThemePalette.of(appearance));
        dialog.setContentPane(panel);
        try {
            transfer = client.attachments().start(request, percent -> {
                if (transferDialog != dialog || !cancel.isEnabled()) return;
                progress.setIndeterminate(false);
                progress.setValue(percent);
                progress.setString(percent == 100 ? "正在完成…" : percent + "%");
            }, result -> {
                if (transferDialog != dialog) return;
                transferDialog = null;
                transfer = null;
                dialog.dispose();
                if (!attachmentSession(revision)) return;
                feedback(result.detail(), result.error());
                if (result.image() != null) showOriginalImage(result.image());
            });
            transferDialog = dialog;
            dialog.pack();
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        } catch (IOException ex) {
            dialog.dispose();
            feedback(ex.getMessage(), true);
        }
    }

    private void closeTransfer() {
        if (transfer != null) transfer.cancel();
        if (transferDialog != null) transferDialog.dispose();
        transfer = null;
        transferDialog = null;
    }

    private void showOriginalImage(java.awt.image.BufferedImage original) {
        JScrollPane view = new JScrollPane(new JLabel(new ImageIcon(original)));
        view.setPreferredSize(new Dimension(Math.min(680, original.getWidth() + 18), Math.min(480, original.getHeight() + 18)));
        ChatTheme.scroll(view, false);
        ChatTheme.apply(view, ThemePalette.of(appearance));
        JOptionPane.showMessageDialog(this, view, "查看原图", JOptionPane.PLAIN_MESSAGE);
    }

    private void recoverMessage(ConversationStore.Entry entry) {
        ConversationId selected = store.selectedId();
        long revision = sessionRevision;
        if (!input.getText().isEmpty() && !input.getText().equals(entry.content())
                && JOptionPane.showConfirmDialog(this, "用取回的正文替换当前草稿？", "取回正文",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        if (revision != sessionRevision || !selected.equals(store.selectedId())) return;
        input.setText(entry.content());
        store.replyDraft(entry.replyTo());
        refreshQuoteDraft();
        input.requestFocusInWindow();
        if (entry.sendStatus() == ConversationStore.SendStatus.UNCONFIRMED)
            feedback("正文已取回。原消息结果待确认，重新发送可能重复。", true);
    }

    private void startReply(ConversationStore.Entry entry) {
        if (entry.messageId() == null || shownMessage(entry.messageId()) != entry
                || !store.canSend() || connectionState != ChatClient.State.CONNECTED) return;
        store.replyDraft(entry.messageId());
        refreshQuoteDraft();
        input.requestFocusInWindow();
    }

    private void copyBody(String text) {
        copyText(text, "已复制正文", this::feedback);
    }

    private void copyInfoIdentifier(String text) {
        copyText(text, "已复制到剪贴板", info::feedback);
    }

    private void copyText(String text, String success, BiConsumer<String, Boolean> showFeedback) {
        try {
            clipboard.get().setContents(new StringSelection(text), null);
        } catch (IllegalStateException | SecurityException ex) {
            showFeedback.accept("无法写入剪贴板，请稍后重试", true);
            return;
        }
        showFeedback.accept(success, false);
    }

    private void refreshQuoteDraft() {
        String id = store.replyDraft();
        quoteDraft.setVisible(id != null);
        if (id != null) {
            ConversationStore.Entry original = shownMessage(id);
            if (original == null) original = store.message(id);
            String label = original == null ? "正在引用一条消息" : MessageTimeline.quoteText(original);
            quoteDraftText.setText(label);
        }
        chatContent.revalidate();
        chatContent.repaint();
    }

    private void selectConversation(ConversationId id) {
        boolean entering = !id.equals(store.selectedId()) || !background.isVisible();
        closeInfo();
        if (entering) {
            closeHistorySearch();
            clearHistoryContext();
        }
        store.saveDraft(input.getText()); store.select(id); shell.openChat();
        refreshList(); refreshConversation(); input.requestFocusInWindow();
        if (!locateUnread() && entering) timeline.scrollToBottom();
        loadConversation(false);
    }
    private void refreshConversationHeader() {
        ConversationStore.Summary selected = store.selected();
        boolean group = selected.id().kind() == ConversationId.Kind.GROUP;
        title.setText(selected.title());
        conversationIdentity.setToolTipText(group ? "查看群聊信息" : "查看用户信息 · @" + selected.id().key());
        conversationIdentity.getAccessibleContext().setAccessibleName(conversationIdentity.getToolTipText());
        more.setVisible(!group);
        timeline.avatarAction(group ? this::showUserInfo : null);
        headerAvatar.setIcon(group ? new Avatar(selected.title(), true, 42)
                : ProfileAvatar.contact(selected.profile(), selected.title(), 42));
        if (signedInAddress != null && connectionState != ChatClient.State.CONNECTED) {
            recipient.setText((group ? "群聊" : "私聊") + " · 暂未连接");
        } else {
            recipient.setText(store.targetLabel() + (selected.id().equals(ConversationId.PUBLIC) ? " · " + store.onlineCount() + " 人在线" : ""));
        }
    }
    private void refreshConversation() {
        refreshConversationHeader();
        restoringDraft = true;
        try { if (!input.getText().equals(store.draft())) input.setText(store.draft()); }
        finally { restoringDraft = false; }
        send.setEnabled(connectionState == ChatClient.State.CONNECTED && store.canSend());
        attach.setEnabled(send.isEnabled());
        image.setEnabled(send.isEnabled());
        chatSearch.setEnabled(connectionState == ChatClient.State.CONNECTED && !deletingHistory);
        input.setToolTipText(connectionState != ChatClient.State.CONNECTED ? "重新登录后可以继续发送"
                : store.canSend() ? "消息 · Enter 发送，Shift+Enter 换行" : store.targetLabel());
        renderTimeline();
    }
    private void renderTimeline() {
        renderTimeline(true);
    }
    private void renderTimeline(boolean keepReadingPosition) {
        timeline.deletion(connectionState == ChatClient.State.CONNECTED && !deletingHistory, this::deleteMessage);
        timeline.quoting(connectionState == ChatClient.State.CONNECTED && !deletingHistory && store.canSend());
        timeline.unreadEntry(unreadAnchor);
        timeline.showEntries(historyContext == null ? store.entries() : historyContext, store.self(), "",
                connectionState == ChatClient.State.CONNECTED, keepReadingPosition);
        refreshQuoteDraft();
        updateHistoryTools();
    }
    private void refreshList() {
        refreshingList = true;
        try {
            listModel.clear(); List<ConversationStore.Summary> summaries = store.summaries(search.getText());
            for (ConversationStore.Summary summary : summaries) {
                if (contactsTab ? store.inContactsList(summary) : store.inPublicList(summary)) listModel.addElement(summary);
            }
            for (int i = 0; i < listModel.size(); i++) if (listModel.get(i).id().equals(store.selectedId())) conversations.setSelectedIndex(i);
            ((CardLayout) listCards.getLayout()).show(listCards, listModel.isEmpty() ? "empty" : "list");
        } finally { refreshingList = false; }
        onlineTitle.setText(store.onlineCount() + " 人在线"); conversations.repaint();
    }

    private void chooseSidebarTab(boolean showContacts) {
        contactsTab = showContacts;
        (showContacts ? contacts : group).setSelected(true);
        refreshList();
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
            if (changedIdentity) { search.setText(""); socialItems = List.of(); chooseSidebarTab(false); }
            restoreAddress(); authentication.switching(false);
            hint.setText("Enter 发送 · Shift+Enter 换行"); hint.setToolTipText(null); ChatTheme.foreground(hint, ThemePalette.Role.MUTED);
            authentication.clearCredentials(false); showChat();
        } else {
            if (state == ChatClient.State.DISCONNECTED) {
                sessionRevision++;
                closeTransfer();
                store.disconnected();
                closeProfile();
                deletingHistory = false; resetHistoryViews(); stopSocial();
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
        if (loggedIn) { reloadHistory(); reloadSocial(); locateUnread(); }
        else if (signedInAddress != null && !client.switching() && state == ChatClient.State.SERVER_CONNECTED && !chatPageVisible())
            authentication.password.requestFocusInWindow();
    }
    private void reloadHistory() {
        resetHistoryViews();
        store.beginHistory();
        refreshHistoryView(false);
        loadConversation(false);
        loadSummaries(null);
    }

    private static final class HistoryPosition {
        boolean loading, loaded, failed;
        String next;
    }

    private void loadSummaries(String cursor) {
        long revision = historyRevision;
        try {
            client.browseHistory(HistoryQuery.SUMMARIES, null, cursor, null, null, page -> {
                if (revision != historyRevision) return;
                if (page.error() != null) { setStatus("会话摘要加载失败：" + page.error(), true); return; }
                for (HistoryPage.Row row : page.rows()) store.summarize(row.record(), row.nickname());
                refreshList();
                refreshConversationHeader();
                if (page.next() != null) loadSummaries(page.next());
            });
        } catch (IOException ex) { setStatus("会话摘要加载失败：" + ex.getMessage(), true); }
    }

    private void loadConversation(boolean older) {
        if (connectionState != ChatClient.State.CONNECTED || deletingHistory || historyContext != null) return;
        ConversationId id = store.selectedId();
        HistoryPosition position = historyPositions.computeIfAbsent(id, key -> new HistoryPosition());
        if (position.loading || (position.loaded && (!older || position.next == null))) return;
        String cursor = position.loaded ? position.next : null;
        position.loading = true;
        updateHistoryTools();
        if (cursor == null) setStatus("正在加载最近消息…", false);
        long revision = historyRevision;
        try {
            client.browseHistory(HistoryQuery.MESSAGES, scope(id), cursor, null, null, page -> {
                if (revision != historyRevision || historyPositions.get(id) != position) return;
                position.loading = false;
                position.failed = page.error() != null;
                if (position.failed) {
                    if (id.equals(store.selectedId())) setStatus(page.error(), true);
                } else {
                    position.loaded = true;
                    position.next = page.next();
                    for (int i = page.rows().size() - 1; i >= 0; i--) {
                        HistoryPage.Row row = page.rows().get(i);
                        store.restore(row.record(), row.nickname());
                    }
                    if (id.equals(store.selectedId())) {
                        refreshHistoryView(cursor != null || historyContext != null || unreadAnchor != null);
                        setStatus(cursor == null ? "最近消息已加载" : "已加载 " + page.rows().size() + " 条更早消息", false);
                    } else refreshList();
                }
                updateHistoryTools();
            });
        } catch (IOException ex) {
            position.loading = false;
            position.failed = true;
            setStatus(ex.getMessage(), true);
            updateHistoryTools();
        }
    }

    private void loadAtTop() {
        HistoryPosition position = historyPositions.get(store.selectedId());
        if (position != null && position.next != null && !position.failed && timeline.getVerticalScrollBar().getValue() == 0)
            loadConversation(true);
    }

    private void updateHistoryTools() {
        HistoryPosition position = historyPositions.get(store.selectedId());
        boolean context = historyContext != null;
        boolean showOlder = !context && connectionState == ChatClient.State.CONNECTED && position != null
                && (position.loading || position.failed || position.next != null);
        olderHistory.setVisible(showOlder);
        olderHistory.setEnabled(showOlder && !position.loading && !deletingHistory);
        if (showOlder) olderHistory.setText(position.loading ? "正在加载…" : position.failed ? "重试加载消息" : "加载更早消息");
        ConversationId selected = store.selectedId();
        boolean showRequest = !context && connectionState == ChatClient.State.CONNECTED && selected.kind() == ConversationId.Kind.PRIVATE
                && store.mayRequestPrivate(selected.key());
        requestPrivate.setVisible(showRequest);
        requestPrivate.setEnabled(showRequest && !privateRequesting);
        int unread = store.selected().unread();
        boolean showUnread = unread > 0;
        newMessages.setText("有 " + unread + " 条新消息");
        newMessages.setVisible(showUnread);
        for (Component component : historyTools.getComponents()) {
            if (component != olderHistory && component != requestPrivate && component != newMessages) component.setVisible(context);
        }
        historyTools.setVisible(context || showOlder || showRequest || showUnread);
        updateLatestButton();
        chatContent.revalidate();
        chatContent.repaint();
    }

    private void updateLatestButton() {
        returnLatest.setVisible(historyContext != null || !timeline.followingLatest());
    }

    private void refreshHistoryView(boolean keepReadingPosition) {
        refreshList(); refreshConversationHeader(); renderTimeline(keepReadingPosition);
    }

    private void openHistorySearch() {
        if (historySearch != null && historySearch.isDisplayable()) { historySearch.toFront(); return; }
        try {
            String selectedScope = scope(store.selectedId());
            historySearch = new HistorySearchDialog(this, store.selected().title(), ThemePalette.of(appearance),
                    (mode, query, cursor, anchor, done) -> client.browseHistory(mode, selectedScope, cursor, query, anchor, done),
                    this::showContext);
            historySearch.setVisible(true);
        } catch (IOException ex) { setStatus(ex.getMessage(), true); }
    }

    private void showContext(String id, HistoryPage page) {
        showContext(id, page, null);
    }

    private void showContext(String id, HistoryPage page, ConversationStore.Entry unread) {
        clearHistoryContext();
        List<ConversationStore.Entry> rows = new ArrayList<>();
        for (int i = page.rows().size() - 1; i >= 0; i--) {
            HistoryPage.Row row = page.rows().get(i);
            rows.add(ConversationStore.archivedEntry(row.record(), row.nickname()));
        }
        historyContext = List.copyOf(rows);
        unreadAnchor = unread;
        timeline.highlight(unread == null ? id : null);
        renderTimeline(false);
        if (unread == null) timeline.revealHighlighted(); else timeline.revealUnread();
    }

    private void showLatest() {
        clearHistoryContext();
        ConversationStore.Unread unread = store.unread();
        if (unread != null) markRead(unread);
        renderTimeline(false);
        loadConversation(false);
    }

    private void clearHistoryContext() {
        locationRevision++;
        historyContext = null;
        unreadAnchor = null;
        unreadLookup = null;
        timeline.highlight(null);
    }

    private ConversationStore.Entry shownMessage(String id) {
        if (historyContext == null) return store.message(id);
        for (ConversationStore.Entry entry : historyContext) if (id.equals(entry.messageId())) return entry;
        return null;
    }

    private void locateOriginal(String id) {
        if (shownMessage(id) != null || store.message(id) != null) {
            if (shownMessage(id) == null) clearHistoryContext();
            locationRevision++;
            unreadLookup = null;
            timeline.highlight(id);
            renderTimeline(true);
            timeline.revealHighlighted();
            return;
        }
        requestContext(id, page -> {
            if (page.error() == null) showContext(id, page);
            else feedback(page.error(), true);
        });
    }

    private boolean locateUnread() {
        if (!readingConversation()) return false;
        ConversationStore.Unread unread = store.unread();
        if (unread == null) return false;
        ConversationStore.Entry first = unread.first();
        if (first == unreadLookup) return true;
        clearHistoryContext();
        if (first.messageId() == null || store.message(first.messageId()) != null) {
            unreadAnchor = first;
            renderTimeline(true);
            timeline.revealUnread();
            markRead(unread);
        } else {
            unreadLookup = first;
            requestContext(first.messageId(), page -> {
                unreadLookup = null;
                if (page.error() == null) {
                    showContext(first.messageId(), page, first);
                    markRead(unread);
                } else feedback(page.error(), true);
            });
        }
        return true;
    }

    private void requestContext(String id, Consumer<HistoryPage> completion) {
        if (connectionState != ChatClient.State.CONNECTED) {
            completion.accept(HistoryPage.failed("请重新连接后查看原消息"));
            return;
        }
        long revision = ++locationRevision;
        ConversationId conversation = store.selectedId();
        try {
            client.browseHistory(HistoryQuery.CONTEXT, scope(conversation), null, null, id, page -> {
                if (revision == locationRevision && conversation.equals(store.selectedId()) && readingConversation())
                    completion.accept(page);
                else if (revision == locationRevision) unreadLookup = null;
            });
        } catch (IOException ex) { completion.accept(HistoryPage.failed(ex.getMessage())); }
    }

    private void markRead(ConversationStore.Unread unread) {
        if (store.markRead(unread.last())) {
            refreshList();
            updateHistoryTools();
        }
    }

    private void readIfAtLatest() {
        ConversationStore.Unread unread = store.unread();
        if (unread != null && historyContext == null && unreadLookup == null && readingConversation() && timeline.followingLatest())
            markRead(unread);
    }

    private void closeHistorySearch() {
        if (historySearch != null) {
            historySearch.dispose();
            historySearch = null;
        }
    }

    private void resetHistoryViews() {
        historyRevision++;
        historyPositions.clear();
        closeHistorySearch();
        clearHistoryContext();
    }

    @Override public void onHistoryDeleted(String scope, String id) {
        deletingHistory = false;
        store.deleteHistory(scope, id);
        chatSearch.setEnabled(true);
        queueInfoRefresh();
        reloadHistory();
    }
    private String scope(ConversationId id) throws IOException {
        if (id.equals(ConversationId.PUBLIC)) return ConversationScope.PUBLIC;
        if (id.kind() == ConversationId.Kind.GROUP) return ConversationScope.group(id.key());
        return ConversationScope.privateChat(store.self(), id.key());
    }
    private void deleteMessage(ConversationStore.Entry entry) {
        if (entry.messageId() == null) return;
        try {
            confirmDelete(scope(store.selectedId()), entry.messageId(), "删除这条信息？");
        } catch (IOException ex) { showError(ex.getMessage()); }
    }
    private void recallMessage(ConversationStore.Entry entry) {
        if (connectionState != ChatClient.State.CONNECTED || entry.messageId() == null
                || !store.self().equals(entry.from()) || shownMessage(entry.messageId()) != entry) return;
        try { client.recallMessage(scope(store.selectedId()), entry.messageId()); }
        catch (IOException ex) { feedback(ex.getMessage(), true); }
    }
    private void clearHistory() {
        try { confirmDelete(scope(store.selectedId()), null, "清空「" + store.selected().title() + "」的本人聊天记录？"); }
        catch (IOException ex) { showError(ex.getMessage()); }
    }
    private void confirmDelete(String scope, String id, String prompt) {
        if (deletingHistory || connectionState != ChatClient.State.CONNECTED) return;
        long revision = sessionRevision; String user = store.self();
        if (JOptionPane.showConfirmDialog(this, prompt + "\n只删除本人的副本，不影响对方或其他成员。删除后无法恢复。", "确认删除聊天记录", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        if (revision != sessionRevision || !user.equals(store.self()) || connectionState != ChatClient.State.CONNECTED) return;
        try {
            client.deleteHistory(scope, id);
            deletingHistory = true;
            chatSearch.setEnabled(false);
            queueInfoRefresh();
            closeHistorySearch();
            renderTimeline();
            setStatus("正在删除本人记录…", false);
        }
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
        deletingHistory = false; resetHistoryViews(); stopSocial();
        closeTransfer();
        sessionRevision++; closeProfile(); store.useIdentity(""); input.setText(""); search.setText("");
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
        refreshList(); refreshConversationHeader(); renderTimeline(true);
        if (value.userId().equals(infoUser) || (infoUser == null && groupRoster != null && (groupRoster.id.equals(ConversationId.PUBLIC)
                || groupRoster.members.stream().anyMatch(row -> value.userId().equals(row.user()))))) queueInfoRefresh();
    }
    @Override public void onGroup(Message message) { receiveChat(message); }
    @Override public void onPrivate(Message message) { receiveChat(message); }
    @Override public void onMessageRecalled(Message message) {
        String id = message.messageId();
        ConversationId target = store.recall(message.conversationId(), id);
        refreshList();
        if (target.equals(store.selectedId())) {
            if (historyContext != null)
                historyContext = historyContext.stream().map(entry -> id.equals(entry.messageId()) ? entry.asRecalled() : entry).toList();
            if (historySearch != null) historySearch.removeRecalled(id);
            renderTimeline();
        }
        if (message.requestId() != null) feedback("消息已撤回", false);
    }
    private void receiveChat(Message message) {
        ConversationId destination = store.receive(message, historyContext == null && unreadLookup == null && timeline.followingLatest());
        refreshList();
        if (destination.equals(store.selectedId())) {
            if (historyContext == null) renderTimeline(true);
            else updateHistoryTools();
        }
    }
    @Override public void onChatSendError(Message message, boolean uncertain) {
        ConversationId target = store.sendError(message.requestId(), uncertain, message.message());
        if (target != null && target.equals(store.selectedId())) renderTimeline(true);
    }
    @Override public void onUserList(Message message) {
        if (store.updateUsers(message.users())) store.notice(store.selectedId(), "私聊对象已离线，草稿保留；请重新选择会话。", false);
        refreshList(); refreshConversation();
        queueInfoRefresh();
        if (socialDialog != null) socialDialog.repaint();
    }
    @Override public void onSystem(Message message) {
        store.system(message.content()); if (store.selectedId().equals(ConversationId.PUBLIC)) renderTimeline(true);
    }
    @Override public void onError(Message message) {
        if (message.requestId() != null && message.requestId().startsWith("recall-")) {
            feedback(message.message(), true);
            return;
        }
        if ("HISTORY_DELETE_FAILED".equals(message.code())) {
            deletingHistory = false;
            chatSearch.setEnabled(connectionState == ChatClient.State.CONNECTED);
            queueInfoRefresh();
        }
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
    private boolean readingConversation() {
        return chatPageVisible() && background.isVisible() && chatContent.isVisible() && (!isVisible() || isFocused());
    }
    private void showChat() {
        if (signedInAddress == null) return;
        serverSelection.cancel();
        if (client.switching()) {
            client.cancelSwitch(); restoreAddress(); authentication.clearCredentials(true); authentication.switching(false);
            updateAuthentication(connectionState, "已返回原账号聊天");
        }
        ((CardLayout) pages.getLayout()).show(pages, "chat");
        store.setVisible(readingConversation()); pages.revalidate(); pages.repaint();
        refreshList(); refreshConversation();
    }
    private void restoreAddress() {
        ClientSessions.Address address = client.address();
        if (address != null) {
            connection.host.setText(address.host());
            connection.port.setText(Integer.toString(address.port()));
            connection.tls.setSelected(address.tls());
        }
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
        closeTransfer();
        client.cancelSwitch();
        client.disconnect(); connectionState = ChatClient.State.DISCONNECTED; signedInAddress = null;
        closeProfile(); profile = null; identity.showProfile(null);
        deletingHistory = false;
        resetHistoryViews();
        stopSocial();
        socialItems = List.of();
        store.useIdentity(""); input.setText(""); search.setText("");
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
        try { scope = scope(selected); }
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

    @Override public void onSocialChanged(Message event) {
        if (event.message() != null) setStatus(event.message(), false);
        if (groupRoster != null) groupRoster.dirty = true;
        reloadSocial();
    }

    private void reloadSocial() {
        if (connectionState != ChatClient.State.CONNECTED) return;
        if (socialLoading) { socialDirty = true; return; }
        socialLoading = true;
        fetchSocial(null, new ArrayList<>(), sessionRevision);
    }

    private void fetchSocial(String cursor, List<SocialItem> rows, long revision) {
        try {
            client.social(SocialAction.STATE, b -> b.cursor(cursor), result -> {
                if (revision != sessionRevision || connectionState != ChatClient.State.CONNECTED) return;
                if (result.error() == null && !socialDirty) {
                    rows.addAll(result.rows());
                    if (result.next() != null) {
                        fetchSocial(result.next(), rows, revision);
                        return;
                    }
                    socialItems = List.copyOf(rows);
                    var joined = store.updateSocial(socialItems);
                    for (ConversationId id : joined) historyPositions.remove(id);
                    if (joined.contains(store.selectedId())) {
                        closeHistorySearch();
                        clearHistoryContext();
                        loadConversation(false);
                    }
                    if (socialDialog != null && socialDialog.isDisplayable()) socialDialog.update(socialItems);
                    refreshList();
                    refreshConversation();
                    updateRequestIndicator();
                    refreshGroupRoster();
                    queueInfoRefresh();
                } else if (result.error() != null) setStatus("好友与群组加载失败：" + result.error(), true);
                socialLoading = false;
                if (socialDirty) { socialDirty = false; reloadSocial(); }
            });
        } catch (IOException ex) {
            socialLoading = false;
            socialDirty = false;
            setStatus(ex.getMessage(), true);
        }
    }

    private void updateRequestIndicator() {
        long requests = incomingRequests();
        requestInbox.setText("通知 (" + requests + ")");
        requestInbox.setVisible(requests > 0);
    }

    private long incomingRequests() {
        return socialItems.stream().filter(row -> switch (row.kind()) {
            case FRIEND_IN, PRIVATE_IN, JOIN_IN, INVITE_IN -> true;
            default -> false;
        }).count();
    }

    private void closeSocialDialog() {
        if (socialDialog != null) { socialDialog.dispose(); socialDialog = null; }
    }

    private void stopSocial() {
        socialLoading = false;
        socialDirty = false;
        privateRequesting = false;
        requestInbox.setVisible(false);
        closeSocialDialog();
        closeInfo();
    }

    private void openSocial(SocialDialog.View view) {
        if (connectionState != ChatClient.State.CONNECTED) return;
        if (socialDialog == null || !socialDialog.isDisplayable()) {
            socialDialog = new SocialDialog(this, ThemePalette.of(appearance), client::social, store::online,
                    user -> { if (!store.self().equals(user)) selectConversation(store.openPrivate(user)); },
                    id -> { chooseSidebarTab(true); selectConversation(store.openGroup(id)); });
        }
        socialDialog.open(view, socialItems);
        socialDialog.toFront();
        reloadSocial();
    }

    private void socialChange(SocialAction action, Consumer<Message.Builder> fields, String success) {
        if (connectionState != ChatClient.State.CONNECTED) return;
        GroupRoster roster = groupRoster;
        String user = infoUser;
        boolean fromInfo = info.isVisible();
        BiConsumer<String, Boolean> report = (message, error) -> {
            feedback(message, error);
            if (fromInfo && info.isVisible() && roster == groupRoster && Objects.equals(user, infoUser)) info.feedback(message, error);
        };
        try {
            client.social(action, fields, result -> report.accept(result.error() == null ? success : result.error(), result.error() != null));
        } catch (IOException ex) { report.accept(ex.getMessage(), true); }
    }

    private void requestPrivate() {
        String peer = store.selectedId().key();
        if (privateRequesting || !store.mayRequestPrivate(peer)) return;
        privateRequesting = true;
        updateHistoryTools();
        try {
            client.social(SocialAction.PRIVATE_REQUEST, b -> b.to(peer), result -> {
                privateRequesting = false;
                if (result.error() != null) updateHistoryTools();
                feedback(result.error() == null ? "已发送私聊请求，等待对方接受" : result.error(), result.error() != null);
            });
        } catch (IOException ex) {
            privateRequesting = false;
            updateHistoryTools();
            feedback(ex.getMessage(), true);
        }
    }

    private void createGroup() {
        JTextField name = ChatTheme.field("", "groupName");
        JCheckBox review = new JCheckBox("入群申请需要管理员审核", true);
        JPanel form = new JPanel(new BorderLayout(0, 6));
        form.add(new JLabel("群组名称"), BorderLayout.NORTH);
        form.add(name, BorderLayout.CENTER);
        form.add(review, BorderLayout.SOUTH);
        long revision = sessionRevision;
        if (JOptionPane.showConfirmDialog(this, form, "创建群组",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION
                || revision != sessionRevision) return;
        socialChange(SocialAction.GROUP_CREATE, b -> b.nickname(name.getText()).content(review.isSelected() ? "REVIEW" : "OPEN"), "群组已创建");
    }

    private static final class GroupRoster {
        final ConversationId id;
        List<SocialItem> members = List.of();
        boolean dirty = true, loading;
        String error;
        GroupRoster(ConversationId id) { this.id = id; }
    }

    private void openConversationInfo() {
        ConversationId id = store.selectedId();
        if (id.kind() == ConversationId.Kind.GROUP) showGroupInfo();
        else showUserInfo(id.key());
    }

    private void showGroupInfo() {
        ConversationId id = store.selectedId();
        if (id.kind() != ConversationId.Kind.GROUP) return;
        if (groupRoster == null) groupRoster = new GroupRoster(id);
        infoUser = null;
        openInfo();
        refreshGroupRoster();
    }

    private void showUserInfo(String user) {
        infoUser = user;
        openInfo();
    }

    private void openInfo() {
        boolean atLatest = historyContext == null && timeline.followingLatest();
        info.setVisible(true);
        info.feedback("", false);
        updateInfo(true);
        chatArea.revalidate(); chatArea.doLayout(); chatArea.repaint();
        if (atLatest) timeline.scrollToBottom();
    }

    private void closeInfo() {
        if (groupTransferDialog != null) groupTransferDialog.dispose();
        groupRoster = null;
        infoUser = null;
        if (!info.isVisible()) return;
        boolean atLatest = historyContext == null && timeline.followingLatest();
        info.setVisible(false);
        chatArea.revalidate(); chatArea.doLayout(); chatArea.repaint();
        if (atLatest) timeline.scrollToBottom();
    }

    private void refreshGroupRoster() {
        GroupRoster roster = groupRoster;
        if (roster == null || roster.id.equals(ConversationId.PUBLIC) || connectionState != ChatClient.State.CONNECTED) return;
        if (!store.joined(roster.id.key())) {
            roster.members = List.of();
            roster.error = null;
            if (infoUser != null) showGroupInfo();
            else queueInfoRefresh();
            return;
        }
        if (roster.loading || !roster.dirty) return;
        roster.dirty = false;
        roster.loading = true;
        roster.error = null;
        queueInfoRefresh();
        fetchGroupMembers(roster, null, new ArrayList<>());
    }

    private void fetchGroupMembers(GroupRoster roster, String cursor, List<SocialItem> rows) {
        try {
            client.social(SocialAction.GROUP_MEMBERS, b -> b.conversationId(ConversationScope.group(roster.id.key())).cursor(cursor), result -> {
                if (closed || roster != groupRoster) return;
                if (store.joined(roster.id.key()) && !roster.dirty && result.error() == null) {
                    rows.addAll(result.rows());
                    if (result.next() != null) {
                        fetchGroupMembers(roster, result.next(), rows);
                        return;
                    }
                    roster.members = List.copyOf(rows);
                }
                roster.loading = false;
                roster.error = result.error();
                if (!socialLoading && roster.dirty) refreshGroupRoster();
                queueInfoRefresh();
            });
        } catch (IOException ex) {
            roster.loading = false;
            roster.error = ex.getMessage();
            queueInfoRefresh();
        }
    }

    private void queueInfoRefresh() {
        if (!info.isVisible() || infoRefreshQueued) return;
        infoRefreshQueued = true;
        SwingUtilities.invokeLater(() -> {
            infoRefreshQueued = false;
            if (!closed && info.isVisible()) updateInfo(false);
        });
    }

    private void retryGroupRoster() {
        if (groupRoster == null) return;
        groupRoster.dirty = true;
        refreshGroupRoster();
    }

    private void updateInfo(boolean resetScroll) {
        if (infoUser != null) {
            String user = infoUser;
            String name = infoName(user);
            String detail = store.online(user) ? "在线" : "离线";
            if (store.self().equals(user)) detail += " · 你";
            else if (store.friend(user)) detail += " · 好友";
            info.showUser(new ConversationInfoPanel.Identity(name, "@" + user, user, detail,
                            ProfileAvatar.contact(store.profile(user), name, 72)),
                    userActions(user), groupRoster != null, resetScroll);
        } else if (groupRoster != null) {
            ConversationId id = groupRoster.id;
            boolean publicRoom = id.equals(ConversationId.PUBLIC);
            SocialItem group = store.groupInfo(id.key());
            boolean joined = publicRoom || store.joined(id.key());
            List<String> users = publicRoom ? store.onlineUsers() : joined
                    ? groupRoster.members.stream().map(SocialItem::user).toList() : List.of();
            List<ConversationInfoPanel.Member> members = users.stream()
                    .sorted(Comparator.comparing((String user) -> !store.online(user)).thenComparing(this::infoName).thenComparing(user -> user))
                    .map(user -> infoMember(user, group == null ? null : group.admin())).toList();
            long online = users.stream().filter(store::online).count();
            String name = store.selected().title();
            String counts = "%d 位成员 · %d 人在线".formatted(users.size(), online);
            String status = null;
            List<ConversationInfoPanel.Action> actions = groupActions(id, group);
            if (!joined) { counts = group == null ? "群聊信息暂不可用" : group.admin() == null ? "群组已解散" : "你已退出群聊"; }
            else if (!publicRoom) {
                if (groupRoster.error != null) {
                    if (members.isEmpty()) counts = "成员人数暂不可用";
                    status = groupRoster.error;
                    actions.add(new ConversationInfoPanel.Action("retryGroupMembers", "重试成员名单", true, this::retryGroupRoster));
                } else if ((groupRoster.loading || groupRoster.dirty) && members.isEmpty()) {
                    counts = "正在加载成员人数…";
                    status = "正在加载成员…";
                }
            }
            String groupNumber = publicRoom ? null : id.key();
            info.showGroup(new ConversationInfoPanel.Identity(name, publicRoom ? "公共聊天室无固定群号" : "群号 " + groupNumber,
                    groupNumber, counts, new Avatar(name, true, 72)), actions, members, status, resetScroll);
            if (groupTransferDialog != null) {
                if (canTransferGroup(group)) groupTransferDialog.updateMembers(members,
                        groupRoster.loading || groupRoster.dirty, groupRoster.error);
                else groupTransferDialog.dispose();
            }
        }
    }

    private String infoName(String user) {
        ContactProfile display = store.profile(user);
        if (display != null) return display.displayName();
        if (groupRoster != null) {
            for (SocialItem row : groupRoster.members) {
                if (user.equals(row.user())) return ContactProfile.displayName(user, row.title());
            }
        }
        return store.self().equals(user) ? ContactProfile.displayName(user, profile == null ? null : profile.nickname())
                : store.privateSummary(user).title();
    }

    private ConversationInfoPanel.Member infoMember(String user, String admin) {
        String name = infoName(user);
        String detail = "@" + user + (store.online(user) ? " · 在线" : " · 离线");
        if (user.equals(admin)) detail += " · 管理员";
        if (user.equals(store.self())) detail += " · 你";
        return new ConversationInfoPanel.Member(user, name, detail, ProfileAvatar.contact(store.profile(user), name, 32));
    }

    private List<ConversationInfoPanel.Action> userActions(String user) {
        if (store.self().equals(user)) return List.of();
        boolean connected = connectionState == ChatClient.State.CONNECTED;
        var message = new ConversationInfoPanel.Action("infoSendMessage", "发送消息", true,
                () -> selectConversation(store.openPrivate(user)));
        if (store.friend(user)) {
            return List.of(message, new ConversationInfoPanel.Action("infoRemoveFriend", "删除好友", connected,
                    () -> confirmRemoveFriend(user)));
        }
        boolean pending = socialItems.stream().anyMatch(row -> user.equals(row.user())
                && (row.kind() == SocialItem.Kind.FRIEND_IN || row.kind() == SocialItem.Kind.FRIEND_OUT));
        return List.of(message, new ConversationInfoPanel.Action("infoRequestFriend", pending ? "好友申请待处理" : "申请好友",
                connected && !pending, () -> socialChange(SocialAction.FRIEND_REQUEST, b -> b.to(user), "好友申请已发送")));
    }

    private void confirmRemoveFriend(String user) {
        long revision = sessionRevision;
        if (JOptionPane.showConfirmDialog(this, "删除好友 @" + user + "？双方仍可查看各自的聊天记录。", "删除好友",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION && revision == sessionRevision) {
            socialChange(SocialAction.FRIEND_REMOVE, b -> b.to(user), "好友关系已解除");
        }
    }

    private List<ConversationInfoPanel.Action> groupActions(ConversationId id, SocialItem group) {
        boolean connected = connectionState == ChatClient.State.CONNECTED;
        List<ConversationInfoPanel.Action> actions = new ArrayList<>();
        actions.add(new ConversationInfoPanel.Action("infoExport", "导出聊天记录", connected, this::exportHistory));
        actions.add(new ConversationInfoPanel.Action("infoClearHistory", DELETE_ALL_HISTORY, connected && !deletingHistory, this::clearHistory));
        actions.add(new ConversationInfoPanel.Action("infoReloadHistory", "重新加载聊天记录", connected && !deletingHistory, this::reloadHistory));
        if (group == null || !Boolean.TRUE.equals(group.member())) return actions;
        String scope = ConversationScope.group(id.key());
        actions.add(new ConversationInfoPanel.Action("groupInvite", "邀请好友", connected, () -> inviteFriend(scope)));
        if (store.self().equals(group.admin())) {
            boolean review = Boolean.TRUE.equals(group.review());
            actions.add(new ConversationInfoPanel.Action("groupReview", review ? "取消入群审核" : "启用入群审核", connected,
                    () -> socialChange(SocialAction.GROUP_REVIEW, b -> b.conversationId(scope).content(review ? "OPEN" : "REVIEW"), "入群设置已保存")));
            actions.add(new ConversationInfoPanel.Action("groupTransfer", "转让管理员", connected, () -> transferGroup(id)));
            actions.add(new ConversationInfoPanel.Action("groupDisband", "解散群组", connected, () -> confirmGroupExit(scope, true)));
        } else actions.add(new ConversationInfoPanel.Action("groupLeave", "退出群聊", connected, () -> confirmGroupExit(scope, false)));
        return actions;
    }

    private void socialItem(ChatPopupMenu popup, String name, String title, Runnable callback) {
        JMenuItem item = popup.action(name, title, LineIcon.Kind.USERS, callback);
        item.setEnabled(connectionState == ChatClient.State.CONNECTED);
    }

    private void inviteFriend(String scope) {
        String[] friends = socialItems.stream().filter(row -> row.kind() == SocialItem.Kind.FRIEND).map(SocialItem::user).toArray(String[]::new);
        if (friends.length == 0) { feedback("暂无可邀请的好友", false); return; }
        long revision = sessionRevision;
        Object value = JOptionPane.showInputDialog(this, "选择好友的用户名", "邀请好友", JOptionPane.PLAIN_MESSAGE, null, friends, friends[0]);
        if (value != null && revision == sessionRevision)
            socialChange(SocialAction.GROUP_INVITE, b -> b.conversationId(scope).to(value.toString()), "邀请已发送，等待好友接受");
    }

    private boolean canTransferGroup(SocialItem group) {
        return connectionState == ChatClient.State.CONNECTED && group != null
                && Boolean.TRUE.equals(group.member()) && store.self().equals(group.admin());
    }

    private void transferGroup(ConversationId id) {
        GroupRoster roster = groupRoster;
        if (groupTransferDialog != null || roster == null || !roster.id.equals(id) || !canTransferGroup(store.groupInfo(id.key()))) return;
        long revision = sessionRevision;
        var dialog = new GroupTransferDialog(this, ThemePalette.of(appearance), store.self(), this::retryGroupRoster);
        groupTransferDialog = dialog;
        try {
            updateInfo(false);
            refreshGroupRoster();
            dialog.setVisible(true);
        } finally {
            groupTransferDialog = null;
            dialog.dispose();
        }
        String user = dialog.recipient();
        if (user != null && !closed && revision == sessionRevision && roster == groupRoster && canTransferGroup(store.groupInfo(id.key()))) {
            socialChange(SocialAction.GROUP_TRANSFER, b -> b.conversationId(ConversationScope.group(id.key())).to(user), "管理员已转让");
        }
    }

    private void confirmGroupExit(String scope, boolean disband) {
        String title = disband ? "解散群组" : "退出群组";
        long revision = sessionRevision;
        if (JOptionPane.showConfirmDialog(this, "确认" + title + "？已授权的旧记录可继续查看。", title,
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION && revision == sessionRevision) {
            socialChange(disband ? SocialAction.GROUP_DISBAND : SocialAction.GROUP_LEAVE,
                    b -> b.conversationId(scope), disband ? "群组已解散" : "已退出群组");
        }
    }

    private void showNewConversation() {
        ChatPopupMenu popup = socialMenu();
        popup.show(create, 0, create.getHeight() + 4);
    }

    private List<ConversationStore.Summary> archivedConversations() {
        return store.summaries("").stream().filter(row -> !store.inPublicList(row) && !store.inContactsList(row))
                .filter(row -> row.time() != null || store.hasDraft(row.id()) || row.id().kind() == ConversationId.Kind.GROUP).toList();
    }

    private void openArchivedConversation() {
        DefaultListModel<ConversationStore.Summary> model = new DefaultListModel<>();
        model.addAll(archivedConversations());
        if (model.isEmpty()) return;
        ConversationList picker = new ConversationList(model);
        picker.setName("archivedConversations");
        picker.setVisibleRowCount(Math.min(5, model.getSize()));
        picker.setSelectedIndex(0);
        JScrollPane scroll = new JScrollPane(picker);
        ChatTheme.scroll(scroll, false);
        ChatTheme.apply(scroll, ThemePalette.of(appearance));
        long revision = sessionRevision;
        if (JOptionPane.showConfirmDialog(this, scroll, OTHER_CONVERSATIONS, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                == JOptionPane.OK_OPTION && revision == sessionRevision && picker.getSelectedValue() != null) {
            selectConversation(picker.getSelectedValue().id());
        }
    }

    ChatPopupMenu socialMenu() {
        ChatPopupMenu popup = new ChatPopupMenu(create, null);
        long requests = incomingRequests();
        socialItem(popup, "socialRequests", requests == 0 ? "通知" : "通知 (" + requests + ")", () -> openSocial(SocialDialog.View.REQUESTS));
        JMenuItem archived = popup.action("archivedConversations", OTHER_CONVERSATIONS, LineIcon.Kind.BACK, this::openArchivedConversation);
        archived.setEnabled(!archivedConversations().isEmpty());
        popup.addSeparator();
        socialItem(popup, "socialFindUser", "查找用户", () -> openSocial(SocialDialog.View.USERS));
        socialItem(popup, "socialFindGroup", "查找群组", () -> openSocial(SocialDialog.View.GROUPS));
        socialItem(popup, "socialCreateGroup", "创建群组", this::createGroup);
        return popup;
    }
    ChatPopupMenu conversationMenu() {
        ChatPopupMenu popup = new ChatPopupMenu(more, store.selected().title());
        ConversationId selected = store.selectedId();
        if (selected.kind() == ConversationId.Kind.GROUP) return popup;
        JMenuItem export = popup.action("chatExport", "导出聊天记录", LineIcon.Kind.SETTINGS, this::exportHistory);
        export.setEnabled(connectionState == ChatClient.State.CONNECTED);
        JMenuItem clear = popup.action("chatClearHistory", DELETE_ALL_HISTORY, LineIcon.Kind.CLOSE, this::clearHistory);
        clear.setEnabled(connectionState == ChatClient.State.CONNECTED && !deletingHistory);
        JMenuItem reload = popup.action("chatReloadHistory", "重新加载聊天记录", LineIcon.Kind.BACK, this::reloadHistory);
        reload.setEnabled(connectionState == ChatClient.State.CONNECTED && !deletingHistory);
        if (!store.friend(selected.key())) {
            popup.addSeparator();
            boolean blocked = store.blocked(selected.key());
            socialItem(popup, "chatBlockPrivate", blocked ? "解除私聊屏蔽" : "屏蔽陌生人私聊",
                    () -> socialChange(blocked ? SocialAction.PRIVATE_UNBLOCK : SocialAction.PRIVATE_BLOCK,
                            b -> b.to(selected.key()), blocked ? "私聊屏蔽已解除" : "已屏蔽私聊"));
        }
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
        if (historySearch != null && historySearch.isDisplayable()) historySearch.applyPalette(palette);
        if (socialDialog != null && socialDialog.isDisplayable()) socialDialog.applyPalette(palette);
        conversations.repaint(); renderTimeline();
        if (persist) {
            try { appearancePreferences.save(value); setStatus("外观设置已保存到本机", false); }
            catch (IOException ex) { feedback("外观已应用，但本地保存失败：" + ex.getMessage(), true); }
        }
    }
    private static Action action(Runnable callback) { return new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { callback.run(); } }; }
    private static DocumentListener changed(Runnable task) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { task.run(); }
            @Override public void removeUpdate(DocumentEvent e) { task.run(); }
            @Override public void changedUpdate(DocumentEvent e) { /* Display attributes do not change the draft. */ }
        };
    }

    private final class ChatContent extends JPanel {
        ChatContent() { super(null); setOpaque(false); }
        @Override public void doLayout() {
            if (header == null) return;
            int inset = getWidth() < 500 ? 10 : 26;
            int width = Math.max(260, Math.min(920, getWidth() - inset * 2)); int x = (getWidth() - width) / 2;
            int top = getWidth() < 500 ? 12 : 24; int headerHeight = historyTools.isVisible() ? 104 : 70;
            header.setBounds(x, top, width, headerHeight);
            Insets border = composer.getInsets();
            int toolWidth = mediaActions.getPreferredSize().width + composeActions.getPreferredSize().width;
            int editorWidth = width - toolWidth - border.left - border.right - 2 * ((BorderLayout) composer.getLayout()).getHgap();
            input.setSize(Math.max(1, editorWidth), Short.MAX_VALUE);
            int editorHeight = Math.min(108, Math.max(24, input.getPreferredSize().height));
            Dimension editorSize = new Dimension(1, editorHeight);
            if (!editorSize.equals(editorScroll.getPreferredSize())) editorScroll.setPreferredSize(editorSize);
            int composeHeight = Math.max(60, editorHeight + 18) + (quoteDraft.isVisible() ? quoteDraft.getPreferredSize().height : 0);
            int composeY = getHeight() - composeHeight - 30;
            composer.setBounds(x, composeY, width, composeHeight);
            timeline.setBounds(x, top + headerHeight + 6, width, Math.max(50, composeY - top - headerHeight - 14));
            returnLatest.setBounds(x + width - 56, composeY - 50, 42, 42);
            hint.setBounds(x + 8, getHeight() - 27, Math.max(150, width - 100), 18);
            counter.setBounds(x + width - 82, getHeight() - 27, 78, 18); counter.setHorizontalAlignment(SwingConstants.RIGHT);
        }
    }

    private void layoutChatArea() {
        int width = chatArea.getWidth(), height = chatArea.getHeight();
        boolean open = info.isVisible();
        boolean alongside = open && width >= 690;
        int cardWidth = alongside ? 300 : Math.min(width - 20, 420);
        chatContent.setVisible(!open || alongside);
        chatContent.setBounds(0, 0, alongside ? width - cardWidth - 22 : width, height);
        info.setBounds(width - cardWidth - (alongside ? 14 : 10), 12, Math.max(0, cardWidth), Math.max(0, height - 24));
        store.setVisible(readingConversation());
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
