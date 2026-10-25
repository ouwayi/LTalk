package cn.ltalk.client.ui;

import cn.ltalk.common.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationStoreTest {
    private ConversationStore store() {
        ConversationStore store = new ConversationStore(); store.useIdentity("alice");
        store.updateUsers(List.of("alice", "bob", "carol")); return store;
    }
    private Message event(MessageType type, String from, String to, String content) {
        return Message.of(type).requestId("same-id-from-different-peers").from(from).to(to).content(content).serverTime("2026-10-25T06:30:00Z").build();
    }

    @Test void routesGroupPrivateEchoesAndUnreadWithoutSwitchingCurrentConversation() {
        ConversationStore store = store();
        store.receive(event(MessageType.GROUP_MESSAGE, "bob", null, "群聊"));
        assertEquals("群聊", store.entries().get(0).content());
        store.receive(event(MessageType.PRIVATE_MESSAGE, "bob", "alice", "仅双方"));
        assertEquals(ConversationId.PUBLIC, store.selectedId()); assertEquals(1, store.entries().size());
        assertEquals(1, store.summaries("bob").stream().filter(s -> s.id().equals(ConversationId.privateWith("bob"))).findFirst().orElseThrow().unread());
        store.select(ConversationId.privateWith("bob")); assertEquals(0, store.selected().unread());
        assertEquals("仅双方", store.entries().get(0).content());
        store.receive(event(MessageType.PRIVATE_MESSAGE, "alice", "bob", "我的回显"));
        assertEquals(2, store.entries().size()); assertEquals(0, store.selected().unread());
        store.receive(event(MessageType.PRIVATE_MESSAGE, "carol", "alice", "另一条私聊"));
        assertEquals(2, store.entries().size()); assertEquals(1, store.summaries("carol").get(0).unread());
    }

    @Test void backgroundAndNarrowListVisibilityDoNotMarkMessagesRead() {
        ConversationStore store = store(); store.setVisible(false);
        store.receive(event(MessageType.GROUP_MESSAGE, "bob", null, "未读1"));
        store.receive(event(MessageType.GROUP_MESSAGE, "carol", null, "未读2"));
        assertEquals(2, store.selected().unread()); store.select(ConversationId.PUBLIC); assertEquals(2, store.selected().unread());
        store.setVisible(true); assertEquals(0, store.selected().unread());
    }

    @Test void draftsHistoryAndOfflineSelectionRemainSeparateAndRequireDeliberateReselection() {
        ConversationStore store = store(); store.saveDraft("群聊草稿");
        store.select(ConversationId.privateWith("bob")); store.saveDraft("私聊草稿\n第二行");
        assertTrue(store.updateUsers(List.of("alice", "carol"))); assertFalse(store.canSend());
        assertEquals(ConversationId.privateWith("bob"), store.selectedId()); assertTrue(store.targetLabel().contains("重新选择"));
        assertEquals("私聊草稿\n第二行", store.draft());
        store.updateUsers(List.of("alice", "bob", "carol")); assertFalse(store.canSend(), "Online again must not silently re-enable old selection");
        assertTrue(store.targetLabel().contains("已上线"));
        store.select(ConversationId.privateWith("bob")); assertTrue(store.canSend());
        store.select(ConversationId.PUBLIC); assertEquals("群聊草稿", store.draft()); assertTrue(store.canSend());
        assertEquals(0, store.summaries("alice").size(), "Self must not be a private conversation");
    }

    @Test void searchUsesNamesAndSummaryAndChangingIdentityClearsPrivateHistory() {
        ConversationStore store = store();
        store.receive(event(MessageType.PRIVATE_MESSAGE, "bob", "alice", "HELLO 中文"));
        assertEquals("bob", store.summaries("hello").get(0).title());
        assertTrue(store.summaries("does not exist").isEmpty());
        store.select(ConversationId.privateWith("bob")); store.saveDraft("敏感草稿");
        store.useIdentity("alice"); assertEquals("敏感草稿", store.draft()); assertEquals(1, store.entries().size());
        store.useIdentity("dave"); assertEquals(ConversationId.PUBLIC, store.selectedId()); assertTrue(store.entries().isEmpty());
        assertEquals("", store.draft()); assertEquals(1, store.summaries("").size());
    }
}
