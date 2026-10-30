package cn.ltalk.client.ui;

import cn.ltalk.common.UserProfile;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Clickable self identity; no connection or account state is stored here. */
public final class UserIdentityButton extends JButton {
    private final JLabel avatar = new JLabel();
    private final JLabel nickname = ChatTheme.label("未登录", 14, ChatTheme.INK, true);
    private final JLabel username = ChatTheme.label("登录后查看个人资料", 11, ChatTheme.MUTED, false);
    public UserIdentityButton() {
        setName("userIdentity"); nickname.setName("identityNickname"); username.setName("identityUsername");
        setLayout(new BorderLayout(12, 0)); setBorder(new EmptyBorder(8, 4, 8, 4));
        setContentAreaFilled(false); setFocusPainted(true); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        JPanel lines = new JPanel(new GridLayout(2, 1, 0, 5)); lines.setOpaque(false); lines.add(nickname); lines.add(username);
        add(avatar, BorderLayout.WEST); add(lines); showProfile(null);
    }
    public void showProfile(UserProfile profile) {
        avatar.setIcon(profile == null ? new Avatar("?", false, 42) : new ProfileAvatar(profile, 42));
        nickname.setText(profile == null ? "未登录" : profile.nickname()); username.setText(profile == null ? "登录后查看个人资料" : "@" + profile.userId());
        setToolTipText(profile == null ? "登录账号" : "个人信息、退出登录与切换账号");
        getAccessibleContext().setAccessibleName(profile == null ? "未登录" : profile.nickname() + "，用户名 " + profile.userId());
    }
}
