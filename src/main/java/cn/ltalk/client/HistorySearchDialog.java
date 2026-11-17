package cn.ltalk.client;

import cn.ltalk.client.ui.ChatTheme;
import cn.ltalk.client.ui.ThemePalette;
import cn.ltalk.common.HistoryQuery;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class HistorySearchDialog extends JDialog {
    @FunctionalInterface
    interface Lookup {
        void request(HistoryQuery mode, String query, String cursor, String anchor, Consumer<HistoryPage> done) throws IOException;
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final JTextField field = ChatTheme.field("", "messageSearch");
    private final JButton search = ChatTheme.primary("搜索");
    private final JButton locate = ChatTheme.pill("定位消息");
    private final JButton more = ChatTheme.pill("更多结果");
    private final JLabel status = ChatTheme.label("输入正文、昵称或用户名", 12, ChatTheme.MUTED, false);
    private final DefaultListModel<HistoryPage.Row> matches = new DefaultListModel<>();
    private final JList<HistoryPage.Row> results = new JList<>(matches);
    private final Lookup lookup;
    private final BiConsumer<String, HistoryPage> onLocated;
    private String query, next;

    HistorySearchDialog(JFrame owner, String conversation, ThemePalette palette, Lookup lookup,
                        BiConsumer<String, HistoryPage> onLocated) {
        super(owner, "搜索消息 · " + conversation, ModalityType.MODELESS);
        this.lookup = lookup;
        this.onLocated = onLocated;
        setName("historySearchDialog");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        ChatTheme.surface(content);
        JPanel entry = new JPanel(new BorderLayout(8, 0));
        entry.setOpaque(false);
        field.getAccessibleContext().setAccessibleName("消息搜索关键词");
        search.setName("submitHistorySearch");
        entry.add(field, BorderLayout.CENTER);
        entry.add(search, BorderLayout.EAST);
        content.add(entry, BorderLayout.NORTH);
        results.setName("historySearchResults");
        results.getAccessibleContext().setAccessibleName("消息搜索结果");
        results.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        results.setFixedCellHeight(34);
        ChatTheme.surface(results);
        ChatTheme.foreground(results, ThemePalette.Role.TEXT);
        results.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                     boolean selected, boolean focused) {
                putClientProperty("html.disable", true);
                return super.getListCellRendererComponent(list, preview((HistoryPage.Row) value), index, selected, focused);
            }
        });
        JScrollPane scroll = new JScrollPane(results);
        ChatTheme.scroll(scroll, false);
        content.add(scroll, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(0, 6));
        footer.setOpaque(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        more.setName("moreHistoryResults");
        locate.setName("locateHistoryResult");
        actions.add(more);
        actions.add(locate);
        footer.add(status, BorderLayout.NORTH);
        footer.add(actions, BorderLayout.SOUTH);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
        applyPalette(palette);
        getRootPane().setDefaultButton(search);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        field.addActionListener(e -> { if (search.isEnabled()) search(false); });
        search.addActionListener(e -> search(false));
        more.addActionListener(e -> search(true));
        locate.addActionListener(e -> locate());
        results.addListSelectionListener(e -> locate.setEnabled(results.isEnabled() && results.getSelectedValue() != null));
        results.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int index = results.locationToIndex(e.getPoint());
                if (e.getClickCount() == 2 && index >= 0 && results.getCellBounds(index, index).contains(e.getPoint())
                        && locate.isEnabled()) locate();
            }
        });
        results.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "locate");
        results.getActionMap().put("locate", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (locate.isEnabled()) locate(); }
        });
        ready(true);
        setSize(Math.max(330, Math.min(owner.getWidth(), 560)), 380);
        setLocationRelativeTo(owner);
    }

    void applyPalette(ThemePalette palette) {
        ChatTheme.apply((JComponent) getContentPane(), palette);
        results.setSelectionBackground(palette.accentSoft());
        results.setSelectionForeground(palette.text());
    }

    private static String preview(HistoryPage.Row row) {
        var record = row.record();
        String name = ContactProfile.displayName(record.sender(), row.nickname());
        String content = record.content().replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        if (content.codePointCount(0, content.length()) > 90) content = content.substring(0, content.offsetByCodePoints(0, 90)) + "…";
        return DATE.format(Instant.parse(record.sentAt())) + "  " + name + "  " + content;
    }

    private void search(boolean append) {
        if (!append) {
            String value = field.getText().strip();
            if (value.isEmpty() || value.length() > HistoryQuery.QUERY_LIMIT) {
                status.setText("请输入 1～" + HistoryQuery.QUERY_LIMIT + " 个字符");
                return;
            }
            query = value;
            next = null;
            matches.clear();
        }
        request(HistoryQuery.SEARCH, query, next, null, "正在搜索…", page -> {
            int first = matches.size();
            for (HistoryPage.Row row : page.rows()) matches.addElement(row);
            next = page.next();
            if (matches.size() > first && results.getSelectedIndex() < 0) results.setSelectedIndex(first);
            status.setText(matches.isEmpty() ? "没有找到消息" : "已显示 " + matches.size() + " 条结果");
        });
    }

    private void locate() {
        HistoryPage.Row row = results.getSelectedValue();
        if (row == null) return;
        String id = row.record().messageId();
        request(HistoryQuery.CONTEXT, null, null, id, "正在定位原消息…", page -> {
            onLocated.accept(id, page);
            dispose();
        });
    }

    private void request(HistoryQuery mode, String query, String cursor, String anchor, String progress, Consumer<HistoryPage> success) {
        ready(false);
        status.setText(progress);
        try {
            lookup.request(mode, query, cursor, anchor, page -> {
                if (!isDisplayable()) return;
                if (page.error() != null) status.setText(page.error());
                else success.accept(page);
                ready(true);
            });
        } catch (IOException ex) {
            status.setText(ex.getMessage());
            ready(true);
        }
    }

    private void ready(boolean enabled) {
        field.setEnabled(enabled);
        search.setEnabled(enabled);
        results.setEnabled(enabled);
        locate.setEnabled(enabled && results.getSelectedValue() != null);
        more.setEnabled(enabled && next != null);
    }
}
