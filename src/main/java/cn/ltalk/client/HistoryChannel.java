package cn.ltalk.client;

import cn.ltalk.common.*;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** Per-connection, bounded history paging and deletion; no Swing or file I/O. */
final class HistoryChannel implements AutoCloseable {
    @FunctionalInterface interface Sender { void send(Message message) throws IOException; }
    private final Sender sender;
    private final Consumer<Runnable> emit;
    private final ClientEventListener listener;
    private final ScheduledExecutorService timer;
    private final Supplier<String> ids, self;
    private final int timeout;
    private String reading, deleting, deleteScope, deleteId;
    private ScheduledFuture<?> readDeadline, deleteDeadline;
    private volatile long generation;
    private volatile boolean closed;
    HistoryChannel(Sender sender, Consumer<Runnable> emit, ClientEventListener listener, ScheduledExecutorService timer,
                   Supplier<String> ids, Supplier<String> self, int timeout) {
        this.sender = sender; this.emit = emit; this.listener = listener; this.timer = timer; this.ids = ids; this.self = self; this.timeout = timeout;
    }
    synchronized void load() {
        if (closed) return;
        cancelRead(); long revision = generation;
        event(() -> { if (generation == revision) listener.onHistoryStarted(); }); page(null);
    }
    private void page(String cursor) {
        reading = "history-" + ids.get(); String expected = reading;
        readDeadline = timer.schedule(() -> { synchronized (this) { if (expected.equals(reading)) readFailed("历史加载超时，可重新加载"); } }, timeout, TimeUnit.MILLISECONDS);
        try { sender.send(Message.of(MessageType.HISTORY_REQUEST).requestId(reading).cursor(cursor).build()); }
        catch (IOException ex) { readFailed(ex.getMessage()); }
    }
    synchronized void delete(String scope, String id) throws IOException {
        if (closed) throw new IOException("连接已关闭");
        if (deleting != null) throw new IOException("已有删除操作，请等待结果");
        if (scope == null) throw new IOException("请选择会话"); ConversationScope.validate(self.get(), scope);
        cancelRead(); deleteScope = scope; deleteId = id; deleting = "delete-" + ids.get(); String expected = deleting;
        deleteDeadline = timer.schedule(() -> { synchronized (this) {
            if (expected.equals(deleting)) deleteFailed("删除结果未确认，请重新加载或登录核对");
        } }, timeout, TimeUnit.MILLISECONDS);
        try { sender.send(Message.of(MessageType.HISTORY_DELETE).requestId(deleting).conversationId(scope).messageId(id).build()); }
        catch (IOException ex) { deleteFailed(ex.getMessage()); throw ex; }
    }
    synchronized boolean receive(Message message) {
        if (message.requestId() != null && message.requestId().equals(reading)) {
            readDeadline.cancel(false);
            try {
                if (message.type() == MessageType.ERROR) throw new IOException(message.message());
                if (message.type() == MessageType.HISTORY_END) {
                    reading = null; long revision = generation;
                    event(() -> { if (generation == revision) listener.onHistoryComplete(); }); return true;
                }
                ChatRecord record = message.record();
                if (message.type() != MessageType.HISTORY_PAGE || record == null || message.cursor() == null) throw new IOException("无效历史响应");
                validate(record); long revision = generation;
                reading = null;
                event(() -> {
                    if (generation != revision) return;
                    listener.onHistoryRecord(record, message.nickname());
                    synchronized (HistoryChannel.this) { if (!closed && generation == revision) page(message.cursor()); }
                });
            } catch (IOException | RuntimeException ex) { readFailed("历史加载失败：" + ex.getMessage()); }
            return true;
        }
        if (message.requestId() != null && message.requestId().equals(deleting)) {
            deleteDeadline.cancel(false);
            if (message.type() != MessageType.HISTORY_DELETED || !Objects.equals(deleteScope, message.conversationId()) || !Objects.equals(deleteId, message.messageId())) {
                deleteFailed(message.type() == MessageType.ERROR ? message.message() : "无效删除响应"); return true;
            }
            deleting = null; String scope = deleteScope, id = deleteId;
            event(() -> listener.onHistoryDeleted(scope, id)); return true;
        }
        return switch (message.type()) { case HISTORY_PAGE, HISTORY_END, HISTORY_DELETED -> true; default -> false; };
    }
    private void validate(ChatRecord record) throws IOException {
        if (record.messageId() == null || record.messageId().isBlank() || record.content() == null || record.sender() == null || record.conversationId() == null)
            throw new IOException("历史记录缺少字段");
        Instant.parse(record.sentAt());
        String expected;
        if ("GROUP_MESSAGE".equals(record.messageType())) expected = ConversationScope.PUBLIC;
        else if ("PRIVATE_MESSAGE".equals(record.messageType()) && (self.get().equals(record.sender()) || self.get().equals(record.recipient())))
            expected = ConversationScope.privateChat(record.sender(), record.recipient());
        else throw new IOException("历史记录类型或权限无效");
        if (!expected.equals(record.conversationId())) throw new IOException("历史会话标识无效");
    }
    private void event(Runnable task) { emit.accept(() -> { if (!closed) task.run(); }); }
    private void cancelRead() { generation++; reading = null; if (readDeadline != null) readDeadline.cancel(false); }
    private void readFailed(String detail) {
        // Keep already received records; stale queued pages cannot be applied after a later reload/delete.
        reading = null; event(() -> listener.onError(Message.error(null, "HISTORY_FAILED", detail)));
    }
    private void deleteFailed(String detail) {
        deleting = null; event(() -> listener.onError(Message.error(null, "HISTORY_DELETE_FAILED", detail)));
    }
    @Override public synchronized void close() {
        closed = true; cancelRead(); deleting = null; if (deleteDeadline != null) deleteDeadline.cancel(false);
    }
}
