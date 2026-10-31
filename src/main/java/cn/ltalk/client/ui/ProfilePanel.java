package cn.ltalk.client.ui;

import cn.ltalk.client.AvatarImporter;
import cn.ltalk.common.UserProfile;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Draft profile editor. Changes become visible in the user bar only after server confirmation. */
public final class ProfilePanel extends JPanel {
    private final UserProfile profile;
    private String avatar;
    private final JTextField nickname;
    private final JLabel preview = new JLabel(), feedback = ChatTheme.label(" ", 12, ChatTheme.MUTED, false);
    private final JButton choose = ChatTheme.pill("修改头像"), reset = ChatTheme.pill("恢复默认头像"), save = ChatTheme.primary("保存资料");
    private SwingWorker<String, Void> importer;
    private boolean closed;
    public ProfilePanel(UserProfile profile, String endpoint, BiConsumer<String, String> onSave, Runnable onCancel) {
        super(new GridBagLayout()); this.profile = profile; avatar = Objects.toString(profile.avatar(), "");
        ChatTheme.surface(this); setName("profilePanel"); setBorder(new EmptyBorder(22, 24, 22, 24));
        nickname = ChatTheme.field(profile.nickname(), "profileNickname"); nickname.setColumns(18);
        choose.setName("profileChooseAvatar"); reset.setName("profileResetAvatar"); save.setName("profileSave"); feedback.setName("profileFeedback");
        preview.setName("profileAvatarPreview"); preview.setIcon(new ProfileAvatar(profile, 72));
        GridBagConstraints c = new GridBagConstraints(); c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        row(ChatTheme.label("个人信息", 22, ChatTheme.INK, true), c); row(preview, c);
        JPanel avatarActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0)); avatarActions.setOpaque(false); avatarActions.add(choose); avatarActions.add(reset); row(avatarActions, c);
        row(ChatTheme.label("用户名：" + profile.userId(), 13, ChatTheme.INK, false), c);
        row(ChatTheme.label("昵称", 12, ChatTheme.MUTED, false), c); row(nickname, c);
        row(ChatTheme.label("注册时间：" + registeredTime(profile.createdAt()), 11, ChatTheme.MUTED, false), c);
        row(ChatTheme.label("服务器：" + endpoint, 11, ChatTheme.MUTED, false), c); row(feedback, c);
        JButton cancel = ChatTheme.pill("取消"); cancel.setName("profileCancel");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); actions.setOpaque(false); actions.add(cancel); actions.add(save); row(actions, c);
        cancel.addActionListener(e -> onCancel.run()); choose.addActionListener(e -> chooseAvatar());
        reset.addActionListener(e -> { avatar = ""; previewAvatar(); });
        save.addActionListener(e -> {
            String name = nickname.getText().strip();
            if (name.isEmpty() || name.length() > 40) { failed("昵称须为 1～40 字符"); return; }
            setBusy(true); feedback.setText("正在保存…"); onSave.accept(name, avatar);
        });
    }
    private void row(Component component, GridBagConstraints c) { c.gridy++; c.insets = new Insets(6, 0, 6, 0); add(component, c); }
    private static String registeredTime(String value) {
        if (value == null) return "早期账号，未记录";
        try { return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value)); }
        catch (java.time.DateTimeException ex) { return "未记录"; }
    }
    private void chooseAvatar() {
        setBusy(true);
        new FileDialogs().choose(this, FileDialogs.Kind.IMAGE, path -> {
            if (closed) return;
            if (path == null) { setBusy(false); return; }
            importAvatar(path);
        }, detail -> { if (!closed) failed(detail); });
    }
    private void importAvatar(java.nio.file.Path path) {
        feedback.setText("正在处理图片…");
        importer = new SwingWorker<>() {
            protected String doInBackground() throws Exception { return AvatarImporter.load(path); }
            protected void done() {
                if (closed) return;
                try { avatar = get(); previewAvatar(); feedback.setText("头像将在保存后生效"); }
                catch (Exception ex) { failed("图片读取失败：" + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage())); }
                finally { setBusy(false); }
            }
        }; importer.execute();
    }
    private void previewAvatar() { preview.setIcon(new ProfileAvatar(new UserProfile(profile.userId(), nickname.getText(), avatar, profile.createdAt()), 72)); }
    private void setBusy(boolean value) { save.setEnabled(!value); choose.setEnabled(!value); reset.setEnabled(!value); nickname.setEnabled(!value); }
    public void failed(String detail) { feedback.setText(detail); feedback.setToolTipText(detail); ChatTheme.foreground(feedback, ThemePalette.Role.DANGER); setBusy(false); }
    public void close() { closed = true; if (importer != null) importer.cancel(true); }
}
