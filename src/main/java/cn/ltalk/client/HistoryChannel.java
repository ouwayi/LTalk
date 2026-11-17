package cn.ltalk.client;

import cn.ltalk.common.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
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
    private final Map<String, ViewRequest> browsing = new HashMap<>();
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
    synchronized void browse(HistoryQuery mode, String scope, String cursor, String query, String anchor,
                             Consumer<HistoryPage> done) throws IOException {
        if (closed) throw new IOException("连接已关闭");
        if (browsing.size() >= 8) throw new IOException("历史请求过多，请稍后重试");
        ConversationScope.validate(self.get(), scope);
        String id = "view-" + ids.get();
        ViewRequest request = new ViewRequest(mode, scope, anchor, done);
        browsing.put(id, request);
        request.deadline = timer.schedule(() -> {
            synchronized (this) {
                if (browsing.containsKey(id)) completeView(id, HistoryPage.failed("历史请求超时，请重试"));
            }
        }, timeout, TimeUnit.MILLISECONDS);
        try {
            sender.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId(id).historyMode(mode.name())
                    .conversationId(scope).cursor(cursor).content(query).messageId(anchor).build());
        } catch (IOException ex) {
            browsing.remove(id);
            request.deadline.cancel(false);
            throw ex;
        }
    }

    private void receiveView(Message message, ViewRequest request) {
        String id = message.requestId();
        try {
            switch (message.type()) {
                case ERROR -> completeView(id, HistoryPage.failed(message.message() == null ? "历史请求失败" : message.message()));
                case HISTORY_VIEW_END -> {
                    validateViewEnd(request, message.cursor());
                    completeView(id, new HistoryPage(List.copyOf(request.rows), message.cursor(), null));
                }
                case HISTORY_VIEW_ROW -> {
                    ChatRecord record = message.record();
                    if (record == null || request.rows.size() >= HistoryQuery.PAGE_SIZE)
                        throw new IOException("无效历史页");
                    validate(record);
                    if (request.mode != HistoryQuery.SUMMARIES && !Objects.equals(request.scope, record.conversationId()))
                        throw new IOException("历史会话不匹配");
                    request.rows.add(new HistoryPage.Row(record, message.nickname()));
                }
                default -> { /* Other events can independently reuse a request identifier. */ }
            }
        } catch (IOException | RuntimeException ex) {
            completeView(id, HistoryPage.failed("历史加载失败：" + ex.getMessage()));
        }
    }

    private static void validateViewEnd(ViewRequest request, String next) throws IOException {
        if (request.mode == HistoryQuery.CONTEXT) {
            if (next != null || request.rows.stream().noneMatch(row -> row.record().messageId().equals(request.anchor)))
                throw new IOException("定位结果中缺少原消息");
        } else if (next != null) {
            if (request.rows.size() != HistoryQuery.PAGE_SIZE) throw new IOException("历史页游标无效");
            ChatRecord last = request.rows.get(request.rows.size() - 1).record();
            String expected = request.mode == HistoryQuery.SUMMARIES ? last.conversationId() : last.messageId();
            if (!next.equals(expected)) throw new IOException("历史页游标无效");
        }
    }

    private void completeView(String id, HistoryPage page) {
        ViewRequest request = browsing.remove(id);
        request.deadline.cancel(false);
        event(() -> request.done.accept(page));
    }

    private static final class ViewRequest {
        final HistoryQuery mode;
        final String scope, anchor;
        final Consumer<HistoryPage> done;
        final List<HistoryPage.Row> rows = new ArrayList<>();
        ScheduledFuture<?> deadline;

        ViewRequest(HistoryQuery mode, String scope, String anchor, Consumer<HistoryPage> done) {
            this.mode = mode;
            this.scope = scope;
            this.anchor = anchor;
            this.done = done;
        }
    }

    synchronized boolean receive(Message message) {
        if (message.type() == MessageType.HISTORY_VIEW_ROW || message.type() == MessageType.HISTORY_VIEW_END
                || (message.type() == MessageType.ERROR && message.requestId() != null && message.requestId().startsWith("view-"))) {
            ViewRequest request = browsing.get(message.requestId());
            if (request != null) receiveView(message, request);
            return true;
        }
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
        if ("GROUP_MESSAGE".equals(record.messageType()) && record.recipient() == null) {
            expected = record.conversationId();
            if (!ConversationScope.PUBLIC.equals(expected)) ConversationScope.groupId(expected);
        }
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
        for (ViewRequest request : browsing.values()) request.deadline.cancel(false);
        browsing.clear();
    }
}
