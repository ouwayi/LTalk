package cn.ltalk.common;

/** One relationship, request, or group returned within the authenticated user's scope. */
public record SocialItem(Kind kind, String id, String user, String group, String title,
                         String admin, Boolean review, Boolean member) {
    public enum Kind {
        USER, FRIEND, PRIVATE_ALLOWED, PRIVATE_BLOCKED,
        FRIEND_IN, FRIEND_OUT, PRIVATE_IN, PRIVATE_OUT, GROUP, JOIN_IN, JOIN_OUT, INVITE_IN, GROUP_MEMBER
    }

    public String key() { return kind.name() + ":" + id; }

    public static SocialItem user(Kind kind, String user, String title) {
        return new SocialItem(kind, user, user, null, title, null, null, null);
    }
}
