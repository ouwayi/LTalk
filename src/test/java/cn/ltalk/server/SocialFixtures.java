package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;

/** Prepares an already-consented pair for tests focused on other features. */
public final class SocialFixtures {
    private SocialFixtures() { }

    public static void permitPrivate(ChatServer server, String first, String second) throws IOException {
        var social = server.social();
        if (social.privateAllowed(first, second)) return;
        social.change(first, SocialAction.PRIVATE_REQUEST, Message.of(MessageType.SOCIAL_REQUEST).to(second).build(),
                server.accounts(), user -> true);
        String request = social.items(second, server.accounts()).stream()
                .filter(row -> row.kind() == SocialItem.Kind.PRIVATE_IN && first.equals(row.user()))
                .findFirst().orElseThrow().id();
        social.change(second, SocialAction.PRIVATE_ACCEPT, Message.of(MessageType.SOCIAL_REQUEST).messageId(request).build(),
                server.accounts(), user -> true);
    }
}
