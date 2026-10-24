package cn.ltalk.client;

import java.util.List;

/** An invalidated private recipient requires a deliberate user choice before any further send. */
public final class ConversationSelection {
    private String target;
    private boolean needsChoice;

    public String target() { return target; }
    public boolean canSend() { return !needsChoice; }
    public void selectGroup() { target = null; needsChoice = false; }
    public void selectPrivate(String user, String self, List<String> online) {
        if (user == null || user.equals(self) || !online.contains(user))
            throw new IllegalArgumentException("请选择在线的其他用户");
        target = user;
        needsChoice = false;
    }
    public boolean updateUsers(List<String> online) {
        if (target != null && !online.contains(target)) {
            target = null;
            needsChoice = true;
            return true;
        }
        return false;
    }
    public String label() {
        if (needsChoice) return "私聊对象已离线 · 请重新选择对象或点击群聊";
        return target == null ? "发送到：群聊（所有在线用户）" : "发送到：私聊 " + target;
    }
}
