package cn.ltalk.client.ui;

import cn.ltalk.common.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryStoreTest {
    @Test void restoredRecordsMergeWithLiveInOrderWithoutDuplicatesOrHistoricalUnreadAndDeletePreservesDraft() {
        ConversationStore store = new ConversationStore(); store.useIdentity("alice"); store.setVisible(false);
        ChatRecord old = new ChatRecord("old", "GROUP:PUBLIC", "bob", null, "old", "GROUP_MESSAGE", "2026-11-01T01:00:00Z");
        ChatRecord recent = new ChatRecord("recent", "GROUP:PUBLIC", "bob", null, "recent", "GROUP_MESSAGE", "2026-11-01T02:00:00Z");
        store.beginHistory(); store.restore(recent); store.restore(old); store.restore(old); assertEquals(0, store.selected().unread());
        Message live = Message.of(MessageType.GROUP_MESSAGE).messageId("recent").from("bob").content("recent").serverTime(recent.sentAt()).build();
        store.receive(live); store.receive(live); assertEquals(1, store.selected().unread()); assertEquals(2, store.entries().size());
        assertEquals("old", store.entries().get(0).content()); assertEquals("recent", store.entries().get(1).content());
        store.saveDraft("keep draft"); store.deleteHistory(ConversationScope.PUBLIC,"old"); assertEquals(1,store.entries().size());
        store.deleteHistory(ConversationScope.PUBLIC,null); assertTrue(store.entries().isEmpty()); assertEquals("keep draft",store.draft());
        store.restore(new ChatRecord("private", "PRIVATE:alice:offline", "offline", "alice", "offline history", "PRIVATE_MESSAGE", old.sentAt()));
        store.select(ConversationId.privateWith("offline")); assertEquals("offline history",store.entries().get(0).content()); assertFalse(store.canSend());
        store.useIdentity("other"); assertTrue(store.entries().isEmpty());
    }
}
