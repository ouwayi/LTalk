package cn.ltalk.client;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConversationSelectionTest {
    @Test void offlinePrivateTargetCannotSilentlyBecomeGroup() {
        ConversationSelection selection = new ConversationSelection();
        assertTrue(selection.canSend());
        selection.selectPrivate("bob", "alice", List.of("alice", "bob"));
        assertEquals("bob", selection.target());
        assertTrue(selection.updateUsers(List.of("alice")));
        assertFalse(selection.canSend()); assertNull(selection.target());
        selection.updateUsers(List.of("alice", "bob"));
        assertFalse(selection.canSend(), "Returning online must not select a recipient automatically");
        selection.selectGroup(); assertTrue(selection.canSend()); assertNull(selection.target());
    }

    @Test void selfAndOfflineSelectionsAreRejectedAndUnrelatedChangesKeepTarget() {
        ConversationSelection selection = new ConversationSelection();
        assertThrows(IllegalArgumentException.class, () -> selection.selectPrivate("alice", "alice", List.of("alice")));
        assertThrows(IllegalArgumentException.class, () -> selection.selectPrivate("bob", "alice", List.of("alice")));
        selection.selectPrivate("bob", "alice", List.of("alice", "bob"));
        assertFalse(selection.updateUsers(List.of("alice", "bob", "carol")));
        assertEquals("bob", selection.target()); assertTrue(selection.canSend());
    }
}
