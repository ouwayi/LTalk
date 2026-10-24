package cn.ltalk.client;

import cn.ltalk.common.Message;

/** Callbacks run on the executor supplied to ChatClient; Swing supplies the EDT. */
public interface ClientEventListener {
    default void onStateChanged(ChatClient.State state, String detail) { }
    default void onGroup(Message message) { }
    default void onPrivate(Message message) { }
    default void onUserList(Message message) { }
    default void onSystem(Message message) { }
    default void onError(Message message) { }
}
