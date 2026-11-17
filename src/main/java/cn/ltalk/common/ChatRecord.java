package cn.ltalk.common;

/** Export schema is separate from transport/control messages. */
public record ChatRecord(String messageId, String conversationId, String sender, String recipient,
                         String content, String messageType, String sentAt, String replyTo, Attachment attachment) {
    public ChatRecord(String messageId, String conversationId, String sender, String recipient,
                      String content, String messageType, String sentAt) {
        this(messageId, conversationId, sender, recipient, content, messageType, sentAt, null);
    }
    public ChatRecord(String messageId, String conversationId, String sender, String recipient,
                      String content, String messageType, String sentAt, String replyTo) {
        this(messageId, conversationId, sender, recipient, content, messageType, sentAt, replyTo, null);
    }
}
