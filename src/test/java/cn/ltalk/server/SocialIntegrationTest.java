package cn.ltalk.server;

import cn.ltalk.common.*;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
class SocialIntegrationTest {
    @TempDir Path data;

    @Test void userDirectoryFindsPartialNamesAndPaginatesRegisteredAccounts() throws Exception {
        try (ChatServer server = start(); Peer alice = Peer.login(server.port(), "alice")) {
            List<String> registered = new ArrayList<>();
            for (int i = 0; i <= SocialAction.PAGE_SIZE; i++) {
                String username = String.format(Locale.ROOT, "Team_%02d", i);
                registered.add(username);
                server.accounts().register(username, i < 2 ? "北辰 Team 小队" : "成员 " + i, "test123!", false);
            }
            assertEquals(registered.subList(0, 2), ok(alice, SocialAction.FIND_USER, m -> m.content(" 辰 TEAM "))
                    .stream().map(SocialItem::user).toList());
            Reply first = call(alice, SocialAction.FIND_USER, m -> m.content("eAm"));
            assertNull(first.error());
            assertEquals(registered.subList(0, SocialAction.PAGE_SIZE), first.rows().stream().map(SocialItem::user).toList());
            assertEquals(first.rows().get(SocialAction.PAGE_SIZE - 1).key(), first.next());
            Reply last = call(alice, SocialAction.FIND_USER, m -> m.content("eAm").cursor(first.next()));
            assertNull(last.error());
            assertNull(last.next());
            assertEquals(List.of(registered.get(SocialAction.PAGE_SIZE)), last.rows().stream().map(SocialItem::user).toList());

            assertEquals(1, server.onlineCount());
            server.accounts().updateProfile(registered.get(0), "夜航小队", "");
            assertEquals(registered.get(0), ok(alice, SocialAction.FIND_USER, m -> m.content("航小")).get(0).user());
            assertTrue(ok(alice, SocialAction.FIND_USER, m -> m.content("alice")).isEmpty());
            assertTrue(ok(alice, SocialAction.FIND_USER, m -> m.content("不存在")).isEmpty());
            assertEquals("MISSING_FIELD", call(alice, SocialAction.FIND_USER, m -> m.content("  ")).error());
            assertEquals("BAD_MESSAGE", call(alice, SocialAction.FIND_USER, m -> m.content("字".repeat(41))).error());
            assertEquals("NOT_FOUND", call(alice, SocialAction.FRIEND_REQUEST, m -> m.to("Team")).error());
        }
    }

    @Test void offlineFriendRequestAndMessagesPersistAndStrangerConsentIsSeparate() throws Exception {
        try (ChatServer server = start(); Peer alice = Peer.login(server.port(), "alice")) {
            server.accounts().register("bob", "Bob", "test123!", false);
            assertEquals("Bob", ok(alice, SocialAction.FIND_USER, m -> m.to("bob")).get(0).title());
            assertEquals("USER_OFFLINE", call(alice, SocialAction.PRIVATE_REQUEST, m -> m.to("bob")).error);
            ok(alice, SocialAction.FRIEND_REQUEST, m -> m.to("bob"));
            assertEquals("REQUEST_PENDING", call(alice, SocialAction.FRIEND_REQUEST, m -> m.to("bob")).error);
        }
        try (ChatServer server = start(); Peer alice = Peer.login(server.port(), "alice")) {
            try (Peer bob = Peer.login(server.port(), "bob"); Peer carol = Peer.login(server.port(), "carol")) {
                alice.privateTo("bob", "no permission yet");
                alice.error("PRIVATE_FORBIDDEN");
                SocialItem incoming = item(bob, SocialItem.Kind.FRIEND_IN, "alice");
                assertEquals("FORBIDDEN", call(carol, SocialAction.FRIEND_ACCEPT, b -> b.messageId(incoming.id())).error);
                ok(bob, SocialAction.FRIEND_REJECT, m -> m.messageId(incoming.id()));
                assertTrue(ok(alice, SocialAction.STATE, b -> {}).stream().noneMatch(row -> row.kind() == SocialItem.Kind.FRIEND_OUT));
                ok(alice, SocialAction.PRIVATE_REQUEST, m -> m.to("bob"));
                assertEquals("REQUEST_PENDING", call(bob, SocialAction.PRIVATE_REQUEST, m -> m.to("alice")).error);
                SocialItem consent = item(bob, SocialItem.Kind.PRIVATE_IN, "alice");
                ok(bob, SocialAction.PRIVATE_REJECT, m -> m.messageId(consent.id()));
                alice.privateTo("bob", "still no permission");
                alice.error("PRIVATE_FORBIDDEN");
                ok(alice, SocialAction.PRIVATE_REQUEST, m -> m.to("bob"));
                ok(bob, SocialAction.PRIVATE_ACCEPT, m -> m.messageId(itemId(bob, SocialItem.Kind.PRIVATE_IN, "alice")));
                assertNotNull(item(alice, SocialItem.Kind.PRIVATE_ALLOWED, "bob"));
                assertTrue(ok(alice, SocialAction.STATE, b -> {}).stream().noneMatch(row -> row.kind() == SocialItem.Kind.FRIEND));
                alice.privateTo("bob", "approved online message");
                alice.type(MessageType.PRIVATE_MESSAGE);
                assertEquals("approved online message", bob.type(MessageType.PRIVATE_MESSAGE).content());
                ok(alice, SocialAction.FRIEND_REQUEST, m -> m.to("bob"));
                ok(bob, SocialAction.FRIEND_ACCEPT, m -> m.messageId(itemId(bob, SocialItem.Kind.FRIEND_IN, "alice")));
            }
            alice.users(1);
            alice.privateTo("bob", "saved while offline");
            assertEquals("saved while offline", alice.type(MessageType.PRIVATE_MESSAGE).content());
        }
        try (ChatServer server = start(); Peer bob = Peer.login(server.port(), "bob")) {
            assertNotNull(item(bob, SocialItem.Kind.FRIEND, "alice"));
            String scope = ConversationScope.privateChat("alice", "bob");
            assertEquals(List.of("approved online message", "saved while offline"), export(bob, scope));
            ok(bob, SocialAction.FRIEND_REMOVE, m -> m.to("alice"));
            bob.privateTo("alice", "offline stranger again");
            bob.error("USER_OFFLINE");
            assertEquals(List.of("approved online message", "saved while offline"), export(bob, scope));
            try (Peer alice = Peer.login(server.port(), "alice")) {
                ok(bob, SocialAction.PRIVATE_BLOCK, m -> m.to("alice"));
                alice.privateTo("bob", "blocked");
                alice.error("PRIVATE_FORBIDDEN");
                assertEquals("PRIVATE_FORBIDDEN", call(alice, SocialAction.PRIVATE_REQUEST, m -> m.to("bob")).error);
                ok(bob, SocialAction.PRIVATE_UNBLOCK, m -> m.to("alice"));
                ok(alice, SocialAction.PRIVATE_REQUEST, m -> m.to("bob"));
                assertNotNull(item(bob, SocialItem.Kind.PRIVATE_IN, "alice"));
            }
        }
    }

    @Test void groupRosterPaginatesCurrentMembersAndRestrictsAccess() throws Exception {
        try (ChatServer server = start(); Peer alice = Peer.login(server.port(), "alice"); Peer bob = Peer.login(server.port(), "bob")) {
            ok(alice, SocialAction.GROUP_CREATE, b -> b.nickname("名单分页").content("OPEN"));
            SocialItem group = ok(alice, SocialAction.STATE, b -> {}).stream().filter(row -> row.kind() == SocialItem.Kind.GROUP)
                    .findFirst().orElseThrow();
            String scope = ConversationScope.group(group.id());
            assertEquals("BAD_MESSAGE", call(alice, SocialAction.GROUP_MEMBERS, b -> b.conversationId(ConversationScope.PUBLIC)).error());
            assertEquals("FORBIDDEN", call(bob, SocialAction.GROUP_MEMBERS, b -> b.conversationId(scope)).error());
            ok(bob, SocialAction.GROUP_REQUEST, b -> b.conversationId(scope));
            List<String> members = new ArrayList<>(List.of("alice", "bob"));
            for (int index = 0; index <= SocialAction.PAGE_SIZE - 2; index++) {
                String user = "member_%02d".formatted(index);
                members.add(user);
                server.accounts().register(user, "离线成员 " + index, "test123!", false);
                server.social().change(user, SocialAction.GROUP_REQUEST, Message.of(MessageType.SOCIAL_REQUEST).conversationId(scope).build(),
                        server.accounts(), ignored -> false);
            }
            Reply first = call(bob, SocialAction.GROUP_MEMBERS, b -> b.conversationId(scope));
            assertNull(first.error());
            assertEquals(members.subList(0, SocialAction.PAGE_SIZE), first.rows().stream().map(SocialItem::user).toList());
            assertTrue(first.rows().stream().allMatch(row -> row.kind() == SocialItem.Kind.GROUP_MEMBER));
            assertEquals(first.rows().get(SocialAction.PAGE_SIZE - 1).key(), first.next());
            Reply last = call(bob, SocialAction.GROUP_MEMBERS, b -> b.conversationId(scope).cursor(first.next()));
            assertNull(last.error());
            assertNull(last.next());
            assertEquals(members.subList(SocialAction.PAGE_SIZE, members.size()), last.rows().stream().map(SocialItem::user).toList());
            assertTrue(bob.received.stream().anyMatch(frame -> frame.type() == MessageType.DISPLAY_PROFILE
                    && "member_00".equals(frame.userId()) && "离线成员 0".equals(frame.nickname())));
            ok(bob, SocialAction.GROUP_LEAVE, b -> b.conversationId(scope));
            assertEquals("FORBIDDEN", call(bob, SocialAction.GROUP_MEMBERS, b -> b.conversationId(scope).cursor(first.next())).error());
            assertFalse(ok(alice, SocialAction.GROUP_MEMBERS, b -> b.conversationId(scope)).stream().anyMatch(row -> "bob".equals(row.user())));
            ok(alice, SocialAction.GROUP_DISBAND, b -> b.conversationId(scope));
            assertEquals("NOT_FOUND", call(alice, SocialAction.GROUP_MEMBERS, b -> b.conversationId(scope)).error());
        }
    }

    @Test void groupsEnforceReviewInvitationRolesAndHistoryWindowsAcrossRestart() throws Exception {
        String scope;
        try (ChatServer server = start(); Peer alice = Peer.login(server.port(), "alice"); Peer bob = Peer.login(server.port(), "bob");
             Peer carol = Peer.login(server.port(), "carol"); Peer dave = Peer.login(server.port(), "dave")) {
            ok(alice, SocialAction.GROUP_CREATE, b -> b.nickname("设计组").content("REVIEW"));
            SocialItem group = ok(bob, SocialAction.FIND_GROUP, b -> b.content("设计")).get(0);
            assertFalse(group.member());
            scope = ConversationScope.group(group.group());
            String groupScope = scope;
            groupChat(bob, scope, "before joining");
            bob.error("FORBIDDEN");
            assertHistoryDenied(bob, scope);
            String earliest = null;
            for (int i = 1; i <= 104; i++) {
                groupChat(alice, scope, "group-" + i);
                Message event = alice.type(MessageType.GROUP_MESSAGE);
                assertEquals(scope, event.conversationId());
                if (i == 1) earliest = event.messageId();
            }
            bob.assertNo(m -> m.type() == MessageType.GROUP_MESSAGE, 45);
            ok(bob, SocialAction.GROUP_REQUEST, m -> m.conversationId(groupScope));
            String pending = item(alice, SocialItem.Kind.JOIN_IN, "bob").id();
            assertEquals("FORBIDDEN", call(carol, SocialAction.GROUP_APPROVE, m -> m.messageId(pending)).error);
            ok(alice, SocialAction.GROUP_REJECT, m -> m.messageId(pending));
            assertHistoryDenied(bob, scope);
            ok(bob, SocialAction.GROUP_REQUEST, m -> m.conversationId(groupScope));
            ok(alice, SocialAction.GROUP_APPROVE, m -> m.messageId(itemId(alice, SocialItem.Kind.JOIN_IN, "bob")));
            List<String> first = export(bob, scope);
            assertEquals(100, first.size());
            assertEquals("group-5", first.get(0));
            assertEquals("group-104", first.get(99));
            bob.send(Message.of(MessageType.GROUP_SEND).requestId("old-quote").conversationId(scope)
                    .content("超出入群范围的引用").replyTo(earliest).build());
            bob.error("NOT_FOUND");
            bob.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("newest").historyMode("MESSAGES").conversationId(scope).build());
            assertEquals("group-104", bob.type(MessageType.HISTORY_VIEW_ROW).record().content());
            bob.type(MessageType.HISTORY_VIEW_END);

            befriend(alice, carol, "alice", "carol");
            ok(alice, SocialAction.GROUP_INVITE, m -> m.conversationId(groupScope).to("carol"));
            String adminInvite = item(carol, SocialItem.Kind.INVITE_IN, "alice").id();
            assertHistoryDenied(carol, scope);
            ok(carol, SocialAction.INVITE_REJECT, m -> m.messageId(adminInvite));
            assertHistoryDenied(carol, scope);
            ok(alice, SocialAction.GROUP_INVITE, m -> m.conversationId(groupScope).to("carol"));
            ok(carol, SocialAction.INVITE_ACCEPT, m -> m.messageId(itemId(carol, SocialItem.Kind.INVITE_IN, "alice")));
            assertEquals(first, export(carol, scope));

            befriend(bob, dave, "bob", "dave");
            assertEquals("FORBIDDEN", call(bob, SocialAction.GROUP_INVITE, m -> m.conversationId(groupScope).to("carol")).error);
            ok(bob, SocialAction.GROUP_INVITE, m -> m.conversationId(groupScope).to("dave"));
            ok(dave, SocialAction.INVITE_ACCEPT, m -> m.messageId(itemId(dave, SocialItem.Kind.INVITE_IN, "bob")));
            assertHistoryDenied(dave, scope);
            ok(alice, SocialAction.GROUP_APPROVE, m -> m.messageId(itemId(alice, SocialItem.Kind.JOIN_IN, "dave")));
            ok(bob, SocialAction.GROUP_LEAVE, m -> m.conversationId(groupScope));
            alice.send(Message.of(MessageType.GROUP_SEND).requestId("quote-after-exit").conversationId(scope)
                    .content("post-exit").replyTo(earliest).build());
            String afterExit = alice.type(MessageType.GROUP_MESSAGE).messageId();
            Message visibleReply = carol.type(MessageType.GROUP_MESSAGE);
            assertEquals(earliest, visibleReply.replyTo());
            assertEquals("post-exit", visibleReply.content());
            assertEquals("post-exit", dave.type(MessageType.GROUP_MESSAGE).content());
            bob.assertNo(m -> m.type() == MessageType.GROUP_MESSAGE, 45);
            assertEquals(first, export(bob, scope));
            bob.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("locate").historyMode("CONTEXT")
                    .conversationId(scope).messageId(afterExit).build());
            bob.error("NOT_FOUND");
            assertEquals("ADMIN_REQUIRED", call(alice, SocialAction.GROUP_LEAVE, m -> m.conversationId(groupScope)).error);
            ok(alice, SocialAction.GROUP_TRANSFER, m -> m.conversationId(groupScope).to("carol"));
            ok(alice, SocialAction.GROUP_LEAVE, m -> m.conversationId(groupScope));
            ok(carol, SocialAction.GROUP_REVIEW, m -> m.conversationId(groupScope).content("OPEN"));
            ok(bob, SocialAction.GROUP_REQUEST, m -> m.conversationId(groupScope));
            assertEquals("post-exit", export(bob, scope).get(100));
            ok(carol, SocialAction.GROUP_DISBAND, m -> m.conversationId(groupScope));
            assertTrue(ok(bob, SocialAction.FIND_GROUP, m -> m.content("设计")).isEmpty());
            groupChat(bob, scope, "after disband");
            bob.error("NOT_FOUND");
        }
        try (ChatServer server = start(); Peer bob = Peer.login(server.port(), "bob")) {
            SocialItem group = ok(bob, SocialAction.STATE, b -> {}).stream().filter(row -> row.kind() == SocialItem.Kind.GROUP).findFirst().orElseThrow();
            assertFalse(group.member());
            assertNull(group.admin());
            assertEquals(101, export(bob, scope).size());
            bob.send(Message.of(MessageType.HISTORY_DELETE).requestId("clear").conversationId(scope).build());
            bob.type(MessageType.HISTORY_DELETED);
            assertTrue(export(bob, scope).isEmpty());
        }
    }

    private ChatServer start() throws IOException {
        ChatServer server = new ChatServer("127.0.0.1", 0);
        server.dataDirectory(data);
        server.start();
        return server;
    }

    private static void befriend(Peer first, Peer second, String sender, String receiver) throws IOException {
        ok(first, SocialAction.FRIEND_REQUEST, m -> m.to(receiver));
        ok(second, SocialAction.FRIEND_ACCEPT, m -> m.messageId(itemId(second, SocialItem.Kind.FRIEND_IN, sender)));
    }

    private static String itemId(Peer peer, SocialItem.Kind kind, String other) {
        try { return item(peer, kind, other).id(); } catch (IOException ex) { throw new AssertionError(ex); }
    }

    private static SocialItem item(Peer peer, SocialItem.Kind kind, String other) throws IOException {
        return ok(peer, SocialAction.STATE, b -> {}).stream()
                .filter(row -> row.kind() == kind && other.equals(row.user())).findFirst().orElseThrow();
    }

    private record Reply(List<SocialItem> rows, String error, String next) { }

    private static List<SocialItem> ok(Peer peer, SocialAction action, Consumer<Message.Builder> fields) throws IOException {
        Reply reply = call(peer, action, fields);
        assertNull(reply.error);
        return reply.rows;
    }

    private static Reply call(Peer peer, SocialAction action, Consumer<Message.Builder> fields) throws IOException {
        String id = UUID.randomUUID().toString();
        Message.Builder request = Message.of(MessageType.SOCIAL_REQUEST).requestId(id).socialAction(action.name());
        fields.accept(request);
        peer.send(request.build());
        List<SocialItem> result = new ArrayList<>();
        while (true) {
            Message frame = peer.await(message -> id.equals(message.requestId()));
            if (frame.type() == MessageType.SOCIAL_END) return new Reply(result, null, frame.cursor());
            if (frame.type() == MessageType.ERROR) return new Reply(result, frame.code(), null);
            assertEquals(MessageType.SOCIAL_ROW, frame.type());
            result.add(frame.socialItem());
        }
    }

    private static List<String> export(Peer peer, String scope) throws IOException {
        List<String> rows = new ArrayList<>();
        String cursor = null;
        while (true) {
            peer.send(Message.of(MessageType.EXPORT_REQUEST).requestId("group-export").conversationId(scope).cursor(cursor).build());
            Message response = peer.await(message -> "group-export".equals(message.requestId()));
            if (response.type() == MessageType.EXPORT_END) return rows;
            assertEquals(MessageType.EXPORT_PAGE, response.type());
            rows.add(response.record().content());
            cursor = response.cursor();
        }
    }

    private static void groupChat(Peer peer, String scope, String text) throws IOException {
        peer.send(Message.of(MessageType.GROUP_SEND).requestId("group-chat").conversationId(scope).content(text).build());
    }

    private static void assertHistoryDenied(Peer peer, String scope) throws IOException {
        peer.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("test").conversationId(scope).historyMode("MESSAGES").build());
        peer.error("FORBIDDEN");
        peer.send(Message.of(MessageType.EXPORT_REQUEST).requestId("export-denied").conversationId(scope).build());
        peer.error("FORBIDDEN");
    }
}
