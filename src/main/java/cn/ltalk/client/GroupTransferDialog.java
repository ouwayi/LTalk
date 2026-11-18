package cn.ltalk.client;

import cn.ltalk.client.ui.ChatTheme;
import cn.ltalk.client.ui.ConversationInfoPanel.Member;
import cn.ltalk.client.ui.ThemePalette;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;

final class GroupTransferDialog extends JDialog {
    private final JTextField search = ChatTheme.field("", "groupTransferSearch");
    private final DefaultListModel<Member> rows = new DefaultListModel<>();
    private final JList<Member> list = new JList<>(rows) {
        @Override protected void processMouseEvent(MouseEvent event) {
            if (event.getID() == MouseEvent.MOUSE_PRESSED) {
                int index = locationToIndex(event.getPoint());
                if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) return;
            }
            super.processMouseEvent(event);
        }
    };
    private final JLabel status = ChatTheme.label("", 12, ChatTheme.MUTED, false);
    private final JButton retry = ChatTheme.pill("重试");
    private final JButton confirm = ChatTheme.primary("确认转让");
    private final String self;
    private List<Member> members = List.of();
    private boolean loading = true;
    private String error, recipient;

    GroupTransferDialog(JFrame owner, ThemePalette palette, String self, Runnable reload) {
        super(owner, "转让管理员", ModalityType.DOCUMENT_MODAL);
        this.self = self;
        setName("groupTransferDialog");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        ChatTheme.surface(content);
        search.putClientProperty("placeholder", "搜索用户名");
        search.getAccessibleContext().setAccessibleName("搜索群成员用户名");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filter(); }
            @Override public void removeUpdate(DocumentEvent e) { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        content.add(search, BorderLayout.NORTH);

        list.setName("groupTransferMembers");
        list.getAccessibleContext().setAccessibleName("群成员");
        list.setFixedCellHeight(34);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectionBackground(palette.accentSoft());
        list.setSelectionForeground(palette.text());
        ChatTheme.surface(list);
        ChatTheme.foreground(list, ThemePalette.Role.TEXT);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> source, Object value, int index,
                                                                     boolean selected, boolean focused) {
                putClientProperty("html.disable", true);
                Member member = (Member) value;
                return super.getListCellRendererComponent(source, member.detail() + "    " + member.name(), index, selected, focused);
            }
        });
        list.addListSelectionListener(e -> updateConfirmation());
        JScrollPane scroll = new JScrollPane(list);
        ChatTheme.scroll(scroll, false);
        content.add(scroll, BorderLayout.CENTER);

        status.setName("groupTransferStatus");
        retry.setName("groupTransferRetry");
        retry.addActionListener(e -> reload.run());
        JPanel feedback = new JPanel(new BorderLayout(8, 0));
        feedback.setOpaque(false);
        feedback.add(status, BorderLayout.CENTER);
        feedback.add(retry, BorderLayout.EAST);
        JButton cancel = ChatTheme.pill("取消");
        cancel.setName("groupTransferCancel");
        cancel.addActionListener(e -> dispose());
        confirm.setName("groupTransferConfirm");
        confirm.addActionListener(e -> {
            if (!confirm.isEnabled()) return;
            recipient = list.getSelectedValue().user();
            dispose();
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(cancel);
        actions.add(confirm);
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.add(feedback, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(confirm);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        ChatTheme.apply(content, palette);
        filter();
        setSize(Math.min(420, owner.getWidth()), 410);
        setMinimumSize(new Dimension(300, 300));
        setLocationRelativeTo(owner);
    }

    void updateMembers(List<Member> members, boolean loading, String error) {
        this.members = members;
        this.loading = loading;
        this.error = error;
        filter();
    }

    String recipient() { return recipient; }

    private void filter() {
        Member selected = list.getSelectedValue();
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        if (query.startsWith("@")) query = query.substring(1);
        rows.clear();
        for (Member member : members) {
            if (!member.user().toLowerCase(Locale.ROOT).contains(query)) continue;
            rows.addElement(member);
            if (selected != null && member.user().equals(selected.user())) list.setSelectedIndex(rows.size() - 1);
        }
        if (list.getSelectedIndex() >= 0) list.ensureIndexIsVisible(list.getSelectedIndex());
        retry.setVisible(!loading && error != null);
        if (loading) status.setText("正在加载群成员…");
        else if (error != null) status.setText(error);
        else if (rows.isEmpty()) status.setText("未找到匹配的群成员");
        else if (members.size() == 1 && members.get(0).user().equals(self)) status.setText("暂无可转让的群成员");
        else status.setText("显示 " + rows.size() + " 位成员");
        ChatTheme.foreground(status, retry.isVisible() ? ThemePalette.Role.DANGER : ThemePalette.Role.MUTED);
        updateConfirmation();
    }

    private void updateConfirmation() {
        Member member = list.getSelectedValue();
        confirm.setEnabled(!loading && error == null && member != null && !self.equals(member.user()));
    }
}
