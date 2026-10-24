package cn.ltalk.common;

import java.util.List;

/** Immutable wire event. Null fields are omitted from JSON. */
public record Message(MessageType type, String requestId, String userId, String from, String to,
                      String content, String serverTime, List<String> users, Long revision,
                      String code, String message) {
    public Message {
        if (users != null) users = List.copyOf(users);
    }

    public static Builder of(MessageType type) { return new Builder(type); }

    public static Message error(String requestId, String code, String message) {
        return of(MessageType.ERROR).requestId(requestId).code(code).message(message).build();
    }

    public static final class Builder {
        private final MessageType type;
        private String requestId, userId, from, to, content, serverTime, code, message;
        private List<String> users;
        private Long revision;

        private Builder(MessageType type) { this.type = type; }
        public Builder requestId(String value) { requestId = value; return this; }
        public Builder userId(String value) { userId = value; return this; }
        public Builder from(String value) { from = value; return this; }
        public Builder to(String value) { to = value; return this; }
        public Builder content(String value) { content = value; return this; }
        public Builder serverTime(String value) { serverTime = value; return this; }
        public Builder users(List<String> value) { users = value; return this; }
        public Builder revision(long value) { revision = value; return this; }
        public Builder code(String value) { code = value; return this; }
        public Builder message(String value) { message = value; return this; }
        public Message build() {
            return new Message(type, requestId, userId, from, to, content, serverTime, users, revision, code, message);
        }
    }
}
