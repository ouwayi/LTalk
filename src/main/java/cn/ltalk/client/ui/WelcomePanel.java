package cn.ltalk.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Two columns on desktop, stacked with scrolling on narrow windows. */
public final class WelcomePanel extends JPanel implements Scrollable {
    private final JPanel columns = new JPanel(null) {
        @Override public void doLayout() {
            if (getComponentCount() != 2) return;
            Component left = getComponent(0), right = getComponent(1);
            if (WelcomePanel.this.getWidth() < 660) {
                int topHeight = left.getPreferredSize().height;
                left.setBounds(0, 0, getWidth(), topHeight);
                right.setBounds(0, topHeight + 18, getWidth(), Math.max(right.getPreferredSize().height, getHeight() - topHeight - 18));
            } else {
                int half = (getWidth() - 24) / 2;
                left.setBounds(0, 0, half, getHeight()); right.setBounds(half + 24, 0, getWidth() - half - 24, getHeight());
            }
        }
    };
    public final JButton back = ChatTheme.pill("返回聊天");
    public WelcomePanel(ConnectionPanel connection, AuthenticationPanel authentication) {
        super(new BorderLayout(0, 22)); setName("welcomePanel"); ChatTheme.surface(this);
        setBorder(new EmptyBorder(28, 28, 28, 28));
        JPanel heading = new JPanel(new BorderLayout()); heading.setOpaque(false);
        heading.add(ChatTheme.label("LTalk", 28, ChatTheme.INK, true)); back.setName("backToChat"); heading.add(back, BorderLayout.EAST); back.setVisible(false);
        add(heading, BorderLayout.NORTH); columns.setOpaque(false); columns.add(connection); columns.add(authentication); add(columns);
    }
    @Override public Dimension getPreferredSize() {
        int width = getParent() == null ? getWidth() : getParent().getWidth();
        int maxHeight = 0, sumHeight = 18;
        for (Component form : columns.getComponents()) { maxHeight = Math.max(maxHeight, form.getPreferredSize().height); sumHeight += form.getPreferredSize().height; }
        int bodyHeight = width > 0 && width < 660 ? sumHeight : maxHeight;
        return new Dimension(880, Math.max(650, bodyHeight + 120));
    }
    public Dimension getPreferredScrollableViewportSize() { return new Dimension(880, 650); }
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return visible.height - 24; }
    public boolean getScrollableTracksViewportWidth() { return true; }
    public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() >= getPreferredSize().height; }
}
