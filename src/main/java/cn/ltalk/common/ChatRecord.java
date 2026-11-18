package cn.ltalk.common;

/** Export schema is separate from transport/control messages. */
public record ChatRecord(String messageId, String conversationId, String sender, String recipient,
                         String content, String messageType, String sentAt, String replyTo, Attachment attachment,
                         Boolean recalled) {
    public static final long RECALL_SECONDS = 60;
    public static final String RECALLED_CONTENT = "[消息已撤回]";

    public ChatRecord(String messageId, String conversationId, String sender, String recipient,
                      String content, String messageType, String sentAt, String replyTo, Attachment attachment) {
        this(messageId, conversationId, sender, recipient, content, messageType, sentAt, replyTo, attachment, null);
    }
    public boolean isRecalled() { return Boolean.TRUE.equals(recalled); }
    public ChatRecord asRecalled() {
        return new ChatRecord(messageId, conversationId, sender, recipient, RECALLED_CONTENT, messageType, sentAt, null, null, true);
    }
    public ChatRecord(String messageId, String conversationId, String sender, String recipient,
                      String content, String messageType, String sentAt) {
        this(messageId, conversationId, sender, recipient, content, messageType, sentAt, null);
    }
    public ChatRecord(String messageId, String conversationId, String sender, String recipient,
                      String content, String messageType, String sentAt, String replyTo) {
        this(messageId, conversationId, sender, recipient, content, messageType, sentAt, replyTo, null);
    }
}
