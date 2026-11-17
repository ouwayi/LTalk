package cn.ltalk.client.ui;

/** Stable UI identity. Additional groups can use another GROUP key without conflating private peers. */
public record ConversationId(Kind kind, String key) {
    public enum Kind { GROUP, PRIVATE }
    public static final ConversationId PUBLIC = new ConversationId(Kind.GROUP, "public");
    public static ConversationId privateWith(String user) { return new ConversationId(Kind.PRIVATE, user); }
    public static ConversationId group(String id) { return new ConversationId(Kind.GROUP, id); }
}
