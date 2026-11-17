package cn.ltalk.client;

import cn.ltalk.common.*;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;

/** EDT-confined session handoff. Authentication callbacks never mutate the old chat presentation. */
public final class ClientSessions implements AutoCloseable {
    public interface Listener extends ClientEventListener {
        default void onSwitchState(ChatClient.State state, String detail) { }
        default void onSwitchError(Message message) { }
        default void onSwitchRegistered(String userId) { }
        default void onSessionReplaced() { }
    }
    public record Address(String host, int port, boolean tls) {
        public Address(String host, int port) { this(host, port, false); }
    }
    private final Listener listener;
    private Slot current, pending;
    private boolean closed;
    public ClientSessions(Listener listener) { this.listener = listener; current = new Slot(); }
    public ChatClient.State state() { return current.client.state(); }
    public boolean switching() { return pending != null; }
    public Address address() { return current.address; }
    Address authenticationAddress() { return authentication().address; }
    private Slot authentication() { return pending == null ? current : pending; }
    public ChatClient.State authenticationState() { return authentication().client.state(); }
    public void connectServer(String host, int port) throws IOException {
        connectServer(host, port, false);
    }
    public void connectServer(String host, int port, boolean tls) throws IOException {
        Slot slot = authentication(); slot.client.connectServer(host, port, tls); slot.address = new Address(host.strip(), port, tls);
    }
    public void beginSwitch() throws IOException {
        if (closed || state() != ChatClient.State.CONNECTED) throw new IOException("请先登录当前账号");
        if (pending != null) throw new IOException("已有账号切换正在进行");
        pending = new Slot();
        try { connectServer(current.address.host(), current.address.port(), current.address.tls()); }
        catch (IOException | RuntimeException ex) { cancelSwitch(); throw ex; }
    }
    public void cancelSwitch() {
        Slot abandoned = pending; pending = null;
        if (abandoned != null) abandoned.client.close();
    }
    public void login(String userId, String password) throws IOException {
        if (pending != null && state() == ChatClient.State.CONNECTED && current.address.equals(pending.address)
                && current.profile != null && current.profile.userId().equals(Validation.userId(userId)))
            throw new IOException("当前账号已登录，可直接返回聊天；请输入其他账号");
        authentication().client.login(userId, password);
    }
    public void registerAndLogin(String userId, String nickname, String password, boolean random) throws IOException {
        authentication().client.registerAndLogin(userId, nickname, password, random);
    }
    public void disconnect() { authentication().client.disconnect(); }
    public String sendGroup(String text) throws IOException { return current.client.sendGroup(text); }
    public String sendPrivate(String target, String text) throws IOException { return current.client.sendPrivate(target, text); }
    public void exportHistory(Path directory, String format, String scope) throws IOException { current.client.exportHistory(directory, format, scope); }
    public void exportTo(Path path, String format, String scope, boolean replace) throws IOException { current.client.exportTo(path, format, scope, replace); }
    public void updateProfile(String nickname, String avatar) throws IOException { current.client.updateProfile(nickname, avatar); }
    public void changePassword(String currentPassword, String newPassword, java.util.function.Consumer<Message> completion) throws IOException {
        Slot slot = current;
        slot.client.changePassword(currentPassword, newPassword, response -> {
            if (slot.active()) completion.accept(response);
        });
    }
    public void loadHistory() throws IOException { current.client.loadHistory(); }
    public void deleteHistory(String scope, String id) throws IOException { current.client.deleteHistory(scope, id); }
    @Override public void close() {
        closed = true; cancelSwitch(); current.client.close();
    }
    private final class Slot implements ClientEventListener {
        final ChatClient client = new ChatClient(this, SwingUtilities::invokeLater);
        Address address;
        UserProfile profile;
        private boolean active() { return !closed && current == this; }
        private boolean candidate() { return !closed && pending == this; }
        @Override public void onStateChanged(ChatClient.State state, String detail) {
            if (active()) listener.onStateChanged(state, detail);
            else if (candidate()) {
                if (state == ChatClient.State.CONNECTED) {
                    if (profile == null || profile.userId() == null) {
                        client.disconnect(); listener.onSwitchError(Message.error(null, "LOGIN_FAILED", "新账号未返回有效资料")); return;
                    }
                    Slot previous = current; current = this; pending = null;
                    // Invalidate the old relay before closing it; queued callbacks cannot cross identities.
                    previous.client.close(); listener.onSessionReplaced(); listener.onProfile(profile);
                    listener.onStateChanged(state, "已切换账号 · " + profile.userId());
                } else listener.onSwitchState(state, detail);
            }
        }
        @Override public void onProfile(UserProfile value) {
            if (!active() && !candidate()) return;
            profile = value; if (active()) listener.onProfile(value);
        }
        @Override public void onRegistered(String userId) {
            if (active()) listener.onRegistered(userId); else if (candidate()) listener.onSwitchRegistered(userId);
        }
        @Override public void onError(Message message) {
            if (active()) listener.onError(message); else if (candidate()) listener.onSwitchError(message);
        }
        @Override public void onChatSendError(Message message, boolean uncertain) {
            if (active()) listener.onChatSendError(message, uncertain);
        }
        @Override public void onGroup(Message message) { if (active()) listener.onGroup(message); }
        @Override public void onPrivate(Message message) { if (active()) listener.onPrivate(message); }
        @Override public void onUserList(Message message) { if (active()) listener.onUserList(message); }
        @Override public void onDisplayProfile(ContactProfile profile) { if (active()) listener.onDisplayProfile(profile); }
        @Override public void onSystem(Message message) { if (active()) listener.onSystem(message); }
        @Override public void onExported(Path path) { if (active()) listener.onExported(path); }
        @Override public void onHistoryStarted() { if (active()) listener.onHistoryStarted(); }
        @Override public void onHistoryRecord(ChatRecord record, String nickname) { if (active()) listener.onHistoryRecord(record, nickname); }
        @Override public void onHistoryComplete() { if (active()) listener.onHistoryComplete(); }
        @Override public void onHistoryDeleted(String scope, String id) { if (active()) listener.onHistoryDeleted(scope, id); }
    }
}
