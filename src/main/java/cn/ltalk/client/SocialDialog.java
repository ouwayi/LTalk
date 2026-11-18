package cn.ltalk.client;

import cn.ltalk.client.ui.ChatTheme;
import cn.ltalk.client.ui.ThemePalette;
import cn.ltalk.common.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Desktop inbox and directory. Authorization is enforced by the server. */
final class SocialDialog extends JDialog {
    @FunctionalInterface
    interface Gateway {
        void request(SocialAction action, Consumer<Message.Builder> fields,
                     Consumer<SocialChannel.Result> done) throws IOException;
    }

    enum View {
        REQUESTS("请求"), FRIENDS("好友"), USERS("查找用户"), GROUPS("查找群组");
        private final String label;
        View(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final Gateway gateway;
    private final Consumer<String> openPrivate, openGroup;
    private final Predicate<String> online;
    private final JComboBox<View> view = new JComboBox<>(View.values());
    private final JTextField query = ChatTheme.field("", "socialQuery");
    private final JButton search = ChatTheme.primary("查找");
    private final JPanel queryBar = new JPanel(new BorderLayout(6, 0));
    private final JButton more = ChatTheme.pill("更多");
    private final JButton primary = ChatTheme.pill("接受");
    private final JButton secondary = ChatTheme.pill("拒绝");
    private final JLabel status = ChatTheme.label("", 12, ChatTheme.MUTED, false);
    private final DefaultListModel<SocialItem> rows = new DefaultListModel<>();
    private final JList<SocialItem> list = new JList<>(rows);
    private List<SocialItem> state = List.of();
    private String searched, next;
    private long revision;
    private boolean busy;

    SocialDialog(JFrame owner, ThemePalette palette, Gateway gateway, Predicate<String> online,
                 Consumer<String> openPrivate, Consumer<String> openGroup) {
        super(owner, "好友、私聊与群组", ModalityType.MODELESS);
        this.gateway = gateway;
        this.online = online;
        this.openPrivate = openPrivate;
        this.openGroup = openGroup;
        setName("socialDialog");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        ChatTheme.surface(content);
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        view.setName("socialView");
        view.getAccessibleContext().setAccessibleName("切换好友、请求和查找页面");
        view.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> source, Object value, int index,
                                                                     boolean selected, boolean focus) {
                super.getListCellRendererComponent(source, value, index, selected, focus);
                ThemePalette palette = ChatTheme.palette(view);
                setBackground(index >= 0 && selected ? palette.accentSoft() : palette.soft());
                setForeground(palette.text());
                return this;
            }
        });
        top.add(view, BorderLayout.NORTH);
        queryBar.setOpaque(false);
        search.setName("socialSearch");
        queryBar.add(query, BorderLayout.CENTER);
        queryBar.add(search, BorderLayout.EAST);
        top.add(queryBar, BorderLayout.SOUTH);
        content.add(top, BorderLayout.NORTH);
        list.setName("socialRows");
        list.getAccessibleContext().setAccessibleName("好友、请求或群组列表");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(34);
        ChatTheme.surface(list);
        ChatTheme.foreground(list, ThemePalette.Role.TEXT);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> source, Object value, int index,
                                                                     boolean selected, boolean focus) {
                putClientProperty("html.disable", true);
                return super.getListCellRendererComponent(source, label((SocialItem) value), index, selected, focus);
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        ChatTheme.scroll(scroll, false);
        content.add(scroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        buttons.setOpaque(false);
        primary.setName("socialPrimary");
        secondary.setName("socialSecondary");
        buttons.add(more);
        buttons.add(secondary);
        buttons.add(primary);
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(buttons, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        applyPalette(palette);
        view.addActionListener(e -> switchView());
        list.addListSelectionListener(e -> buttons());
        search.addActionListener(e -> search(false));
        query.addActionListener(e -> { if (!busy) search(false); });
        more.addActionListener(e -> search(true));
        primary.addActionListener(e -> act(true));
        secondary.addActionListener(e -> act(false));
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(Math.max(345, Math.min(owner.getWidth(), 520)), 430);
        setLocationRelativeTo(owner);
    }

    void applyPalette(ThemePalette palette) {
        ChatTheme.apply((JComponent) getContentPane(), palette);
        list.setSelectionBackground(palette.accentSoft());
        list.setSelectionForeground(palette.text());
        view.setForeground(palette.text());
        view.setBackground(palette.soft());
    }

    void open(View page, List<SocialItem> items) {
        state = items;
        view.setSelectedItem(page);
        switchView();
        setVisible(true);
    }

    void update(List<SocialItem> items) {
        state = items;
        if (!browsing()) fillState();
        if (view.getSelectedItem() == View.GROUPS) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                String id = rows.get(i).id();
                SocialItem current = state.stream().filter(row -> row.kind() == SocialItem.Kind.GROUP && id.equals(row.id()))
                        .findFirst().orElse(null);
                if (current != null) {
                    if (current.admin() == null) rows.remove(i); else rows.set(i, current);
                }
            }
        }
        buttons();
    }

    private boolean browsing() { return view.getSelectedItem() == View.USERS || view.getSelectedItem() == View.GROUPS; }

    private void switchView() {
        revision++;
        busy = false;
        rows.clear();
        next = null;
        query.setText("");
        boolean userSearch = view.getSelectedItem() == View.USERS;
        query.putClientProperty("placeholder", userSearch ? "昵称或用户名" : "群名或群组编号");
        queryBar.setVisible(browsing());
        if (browsing()) status.setText(userSearch ? "可输入昵称或用户名的一部分" : "留空可浏览群组");
        else {
            fillState();
            status.setText(rows.isEmpty() ? (view.getSelectedItem() == View.FRIENDS ? "暂无好友" : "暂无请求") : " ");
        }
        buttons();
    }

    private void fillState() {
        SocialItem selected = list.getSelectedValue();
        rows.clear();
        for (SocialItem item : state) {
            if (view.getSelectedItem() == View.FRIENDS ? item.kind() == SocialItem.Kind.FRIEND : isRequest(item.kind()))
                rows.addElement(item);
        }
        if (selected != null) list.setSelectedValue(selected, true);
    }

    private static boolean isRequest(SocialItem.Kind kind) {
        return switch (kind) {
            case FRIEND_IN, FRIEND_OUT, PRIVATE_IN, PRIVATE_OUT, JOIN_IN, JOIN_OUT, INVITE_IN -> true;
            default -> false;
        };
    }

    private String label(SocialItem item) {
        String peer = item.user() == null ? "" : " @" + item.user();
        return switch (item.kind()) {
            case USER, FRIEND -> Objects.toString(item.title(), item.user()) + peer + (online.test(item.user()) ? " · 在线" : " · 离线");
            case GROUP -> item.title() + " · @" + item.admin()
                    + (Boolean.TRUE.equals(item.member()) ? " · 已加入" : Boolean.TRUE.equals(item.review()) ? " · 需审核" : " · 可直接加入");
            case FRIEND_IN -> "收到好友申请" + peer;
            case FRIEND_OUT -> "好友申请待处理" + peer;
            case PRIVATE_IN -> "收到私聊请求" + peer;
            case PRIVATE_OUT -> "私聊请求待处理" + peer;
            case JOIN_IN -> "入群申请" + peer + " · " + item.title();
            case JOIN_OUT -> "入群申请待审核 · " + item.title();
            case INVITE_IN -> "入群邀请" + peer + " · " + item.title();
            default -> item.id();
        };
    }

    private void search(boolean append) {
        if (!append) {
            searched = query.getText().strip();
            next = null;
            rows.clear();
        }
        boolean users = view.getSelectedItem() == View.USERS;
        if (users && searched.isEmpty()) {
            status.setText("请输入昵称或用户名");
            buttons();
            return;
        }
        request(users ? SocialAction.FIND_USER : SocialAction.FIND_GROUP,
                builder -> builder.content(searched).cursor(next),
                result -> {
                    result.rows().forEach(rows::addElement);
                    next = result.next();
                    if (!rows.isEmpty() && list.isSelectionEmpty()) list.setSelectedIndex(0);
                    status.setText(rows.isEmpty() ? "没有找到结果" : "已显示 " + rows.size() + " 项");
                });
    }

    private void buttons() {
        SocialItem row = list.getSelectedValue();
        String first = row == null ? null : switch (row.kind()) {
            case USER -> "申请好友";
            case FRIEND -> "打开私聊";
            case GROUP -> Boolean.TRUE.equals(row.member()) ? "打开群聊" : "申请入群";
            case FRIEND_IN, PRIVATE_IN, JOIN_IN, INVITE_IN -> "接受";
            default -> null;
        };
        String second = row == null ? null : switch (row.kind()) {
            case USER -> "打开私聊";
            case FRIEND -> "解除好友";
            case FRIEND_IN, PRIVATE_IN, JOIN_IN, INVITE_IN -> "拒绝";
            default -> null;
        };
        primary.setVisible(first != null);
        secondary.setVisible(second != null);
        primary.setText(first);
        secondary.setText(second);
        primary.setEnabled(!busy && first != null);
        secondary.setEnabled(!busy && second != null);
        search.setEnabled(!busy);
        query.setEnabled(!busy);
        list.setEnabled(!busy);
        more.setVisible(browsing() && next != null);
        more.setEnabled(!busy && next != null);
    }

    private void act(boolean accept) {
        SocialItem row = list.getSelectedValue();
        if (row == null) return;
        switch (row.kind()) {
            case USER -> {
                if (accept) change(SocialAction.FRIEND_REQUEST, b -> b.to(row.user()), "好友申请已发送");
                else { openPrivate.accept(row.user()); dispose(); }
            }
            case FRIEND -> {
                if (accept) { openPrivate.accept(row.user()); dispose(); }
                else if (JOptionPane.showConfirmDialog(this, "解除与 @" + row.user() + " 的好友关系？", "解除好友",
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                    change(SocialAction.FRIEND_REMOVE, b -> b.to(row.user()), "好友关系已解除");
            }
            case GROUP -> {
                if (Boolean.TRUE.equals(row.member())) { openGroup.accept(row.group()); dispose(); }
                else change(SocialAction.GROUP_REQUEST, b -> b.conversationId(ConversationScope.group(row.group())), "已提交入群操作");
            }
            case FRIEND_IN -> decide(row, accept, SocialAction.FRIEND_ACCEPT, SocialAction.FRIEND_REJECT);
            case PRIVATE_IN -> decide(row, accept, SocialAction.PRIVATE_ACCEPT, SocialAction.PRIVATE_REJECT);
            case JOIN_IN -> decide(row, accept, SocialAction.GROUP_APPROVE, SocialAction.GROUP_REJECT);
            case INVITE_IN -> decide(row, accept, SocialAction.INVITE_ACCEPT, SocialAction.INVITE_REJECT);
            default -> { }
        }
    }

    private void decide(SocialItem row, boolean yes, SocialAction accept, SocialAction reject) {
        change(yes ? accept : reject, builder -> builder.messageId(row.id()), yes ? "已接受" : "已拒绝");
    }

    private void change(SocialAction action, Consumer<Message.Builder> fields, String success) {
        request(action, fields, result -> status.setText(success));
    }

    private void request(SocialAction action, Consumer<Message.Builder> fields, Consumer<SocialChannel.Result> success) {
        busy = true;
        buttons();
        status.setText("正在等待服务器…");
        long expected = revision;
        try {
            gateway.request(action, fields, result -> {
                if (!isDisplayable() || revision != expected) return;
                busy = false;
                if (result.error() == null) success.accept(result); else status.setText(result.error());
                buttons();
            });
        } catch (IOException ex) {
            busy = false;
            status.setText(ex.getMessage());
            buttons();
        }
    }
}
