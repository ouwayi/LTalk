package cn.ltalk.common;

/** Export schema is separate from transport/control messages. */
public record ChatRecord(String messageId, String conversationId, String sender, String recipient,
                         String content, String messageType, String sentAt, String replyTo) {
    public ChatRecord(String messageId, String conversationId, String sender, String recipient,
                      String content, String messageType, String sentAt) {
        this(messageId, conversationId, sender, recipient, content, messageType, sentAt, null);
    }
}
