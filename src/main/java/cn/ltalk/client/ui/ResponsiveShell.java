package cn.ltalk.client.ui;

import javax.swing.*;
import java.awt.*;

/** A two-pane desktop becomes a navigable list/chat pair below 760 logical pixels. */
public final class ResponsiveShell extends JPanel {
    public static final int COMPACT_WIDTH = 760;
    private final JComponent sidebar, chat;
    private boolean chatOpen;
    private Runnable layoutListener = () -> { };
    public ResponsiveShell(JComponent sidebar, JComponent chat) {
        super(null); this.sidebar = sidebar; this.chat = chat; add(sidebar); add(chat); setName("shell");
    }
    public boolean compact() { return getWidth() < COMPACT_WIDTH; }
    public boolean chatOpen() { return chatOpen; }
    public void openChat() { chatOpen = true; revalidate(); doLayout(); repaint(); }
    public void openList() { chatOpen = false; revalidate(); doLayout(); repaint(); }
    public void onLayout(Runnable listener) { layoutListener = listener; }
    @Override public void doLayout() {
        if (compact()) {
            sidebar.setVisible(!chatOpen); chat.setVisible(chatOpen);
            sidebar.setBounds(0, 0, getWidth(), getHeight()); chat.setBounds(0, 0, getWidth(), getHeight());
        } else {
            int sideWidth = Math.min(354, Math.max(300, (int) (getWidth() * .28)));
            sidebar.setVisible(true); chat.setVisible(true);
            sidebar.setBounds(0, 0, sideWidth, getHeight()); chat.setBounds(sideWidth, 0, getWidth() - sideWidth, getHeight());
        }
        layoutListener.run();
    }
}
