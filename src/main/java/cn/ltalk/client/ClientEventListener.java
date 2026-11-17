package cn.ltalk.client;

import cn.ltalk.common.Message;
import cn.ltalk.common.ChatRecord;

/** Callbacks run on the executor supplied to ChatClient; Swing supplies the EDT. */
public interface ClientEventListener {
    default void onStateChanged(ChatClient.State state, String detail) { }
    default void onGroup(Message message) { }
    default void onPrivate(Message message) { }
    default void onUserList(Message message) { }
    default void onSystem(Message message) { }
    default void onSocialChanged(Message event) { }
    default void onError(Message message) { }
    default void onChatSendError(Message message, boolean uncertain) { onError(message); }
    default void onRegistered(String userId) { }
    default void onExported(java.nio.file.Path path) { }
    default void onProfile(cn.ltalk.common.UserProfile profile) { }
    default void onDisplayProfile(ContactProfile profile) { }
    default void onHistoryStarted() { }
    default void onHistoryRecord(ChatRecord record) { }
    default void onHistoryRecord(ChatRecord record, String nickname) { onHistoryRecord(record); }
    default void onHistoryComplete() { }
    default void onHistoryDeleted(String scope, String messageId) { }
}
