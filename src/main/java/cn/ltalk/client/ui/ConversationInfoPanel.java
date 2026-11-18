package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/** Details for the current group or a user selected from the chat. */
public final class ConversationInfoPanel extends ChatTheme.RoundedPanel {
    public record Identity(String name, String identifier, String detail, Icon avatar) { }
    public record Action(String id, String label, boolean enabled, Runnable run) { }
    public record Member(String user, String name, String detail, Icon avatar) { }

    private final JLabel heading = ChatTheme.label("", 16, ChatTheme.INK, true);
    private final JLabel feedback = ChatTheme.label("", 12, ChatTheme.MUTED, false);
    private final JButton back = ChatTheme.iconButton(LineIcon.Kind.BACK, "返回群聊信息");
    private final Body body = new Body();
    private final JScrollPane scroll = new JScrollPane(body);
    private final Consumer<String> openMember;

    public ConversationInfoPanel(Runnable close, Runnable backToGroup, Consumer<String> openMember) {
        super(new BorderLayout(), 24, Color.WHITE, true);
        this.openMember = openMember;
        setName("conversationInfo");
        setBorder(new EmptyBorder(8, 12, 10, 12));
        JButton closeButton = ChatTheme.iconButton(LineIcon.Kind.CLOSE, "关闭信息卡片");
        closeButton.setName("closeConversationInfo");
        closeButton.addActionListener(e -> close.run());
        back.setName("backToGroupInfo");
        back.addActionListener(e -> backToGroup.run());
        JPanel toolbar = new JPanel(new BorderLayout(8, 0));
        toolbar.setOpaque(false);
        heading.setName("infoHeading");
        toolbar.add(heading, BorderLayout.CENTER);
        toolbar.add(back, BorderLayout.WEST);
        toolbar.add(closeButton, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);
        ChatTheme.scroll(scroll, true);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scroll);
        feedback.setBorder(new EmptyBorder(4, 5, 2, 5));
        feedback.setVisible(false);
        add(feedback, BorderLayout.SOUTH);
    }

    public void feedback(String message, boolean error) {
        feedback.setText(message);
        feedback.setToolTipText(message.isEmpty() ? null : message);
        feedback.setVisible(!message.isEmpty());
        ChatTheme.foreground(feedback, error ? ThemePalette.Role.DANGER : ThemePalette.Role.MUTED);
    }

    public void showGroup(Identity identity, List<Action> actions, List<Member> members, String rosterStatus, boolean resetScroll) {
        showDetails("群聊信息", identity, actions, false);
        body.add(Box.createVerticalStrut(18));
        body.add(line("群成员", "infoMembersHeading", 14, ChatTheme.INK, true));
        body.add(Box.createVerticalStrut(6));
        if (rosterStatus != null) body.add(line(rosterStatus, "infoRosterStatus", 12, ChatTheme.MUTED, false));
        for (Member member : members) body.add(memberButton(member));
        finish(resetScroll);
    }

    public void showUser(Identity identity, List<Action> actions, boolean fromGroup, boolean resetScroll) {
        showDetails("用户信息", identity, actions, fromGroup);
        finish(resetScroll);
    }

    private void showDetails(String title, Identity identity, List<Action> actions, boolean showBack) {
        heading.setText(title);
        back.setVisible(showBack);
        body.removeAll();
        JLabel avatar = line("", "infoAvatar", 14, ChatTheme.INK, false);
        avatar.setIcon(identity.avatar());
        body.add(avatar);
        body.add(Box.createVerticalStrut(12));
        body.add(line(identity.name(), "infoName", 18, ChatTheme.INK, true));
        body.add(Box.createVerticalStrut(4));
        body.add(line(identity.identifier(), "infoIdentifier", 11, ChatTheme.MUTED, false));
        body.add(Box.createVerticalStrut(4));
        body.add(line(identity.detail(), "infoDetail", 12, ChatTheme.MUTED, false));
        if (!actions.isEmpty()) body.add(Box.createVerticalStrut(16));
        for (int start = 0; start < actions.size(); start += 2) {
            JPanel row = new JPanel(new GridLayout(1, 2, 6, 0));
            row.setOpaque(false);
            row.setAlignmentX(CENTER_ALIGNMENT);
            for (int i = start; i < Math.min(start + 2, actions.size()); i++) {
                Action action = actions.get(i);
                JButton button = ChatTheme.pill(action.label());
                button.setName(action.id());
                button.setEnabled(action.enabled());
                button.addActionListener(e -> action.run().run());
                row.add(button);
            }
            if (row.getComponentCount() == 1 && actions.size() > 1) row.add(Box.createGlue());
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            body.add(row);
            body.add(Box.createVerticalStrut(6));
        }
    }

    private JButton memberButton(Member member) {
        JButton button = ChatTheme.iconButton(LineIcon.Kind.USERS, member.name() + " · @" + member.user());
        button.setToolTipText(null);
        button.setName("groupMember-" + member.user());
        button.setIcon(null);
        button.setLayout(new BorderLayout(9, 0));
        button.setBorder(new EmptyBorder(6, 8, 6, 8));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        button.setAlignmentX(CENTER_ALIGNMENT);
        button.add(new JLabel(member.avatar()), BorderLayout.WEST);
        JPanel labels = new JPanel(new GridLayout(2, 1));
        labels.setOpaque(false);
        labels.add(ChatTheme.label(member.name(), 13, ChatTheme.INK, true));
        labels.add(ChatTheme.label(member.detail(), 11, ChatTheme.MUTED, false));
        button.add(labels);
        button.addActionListener(e -> openMember.accept(member.user()));
        return button;
    }

    private static JLabel line(String text, String id, int size, Color color, boolean bold) {
        JLabel label = ChatTheme.label(text, size, color, bold);
        label.setName(id);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setAlignmentX(CENTER_ALIGNMENT);
        return label;
    }

    private void finish(boolean resetScroll) {
        if (resetScroll) scroll.getViewport().setViewPosition(new Point());
        ChatTheme.refresh(body);
        body.revalidate();
    }

    private static final class Body extends JPanel implements Scrollable {
        Body() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(10, 5, 8, 5));
            setOpaque(false);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 24); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
