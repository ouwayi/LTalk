package cn.ltalk.client.ui;

import cn.ltalk.common.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HistoryStoreTest {
    @Test void lateOwnEchoReplacesUnconfirmedRowAndKeepsConfirmationAcrossHistoryReload() {
        ConversationStore store = new ConversationStore();
        store.useIdentity("alice");
        store.submitted(ConversationId.PUBLIC, "chat-1", "repeat");
        store.sendError("chat-1", true, "未收到回显");
        String time = "2026-11-16T01:00:00Z";
        store.receive(Message.of(MessageType.GROUP_MESSAGE).requestId("chat-1").messageId("other")
                .from("bob").content("repeat").serverTime(time).build());
        assertTrue(store.entries().stream().anyMatch(entry -> entry.sendStatus() == ConversationStore.SendStatus.UNCONFIRMED));
        ChatRecord record = new ChatRecord("own", ConversationScope.PUBLIC, "alice", null, "repeat", "GROUP_MESSAGE", time);
        store.restore(record);
        store.receive(Message.of(MessageType.GROUP_MESSAGE).requestId("chat-1").messageId("own")
                .from("alice").content("repeat").serverTime(time).build());
        assertEquals(2, store.entries().size());
        assertNull(store.sendError("chat-1", false, "迟到错误"));
        store.beginHistory();
        store.restore(record);
        assertEquals(ConversationStore.SendStatus.CONFIRMED, store.entries().get(0).sendStatus());
        store.submitted(ConversationId.PUBLIC, "chat-2", "unsent");
        store.deleteHistory(ConversationScope.PUBLIC, null);
        assertEquals("unsent", store.entries().get(0).content());
        store.useIdentity("bob");
        assertTrue(store.entries().isEmpty());
    }

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
