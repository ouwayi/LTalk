package cn.ltalk.client;

import cn.ltalk.common.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class SocialChannel implements AutoCloseable {
    record Result(List<SocialItem> rows, String next, String error) { }

    private record Pending(List<SocialItem> rows, Consumer<Result> completion, ScheduledFuture<?> deadline) { }
    private final Map<String, Pending> requests = new HashMap<>();
    private final HistoryChannel.Sender sender;
    private final Consumer<Runnable> emit;
    private final ScheduledExecutorService timer;
    private final int timeout;
    private volatile boolean closed;

    SocialChannel(HistoryChannel.Sender sender, Consumer<Runnable> emit, ScheduledExecutorService timer, int timeout) {
        this.sender = sender;
        this.emit = emit;
        this.timer = timer;
        this.timeout = timeout;
    }

    synchronized void request(SocialAction action, Consumer<Message.Builder> fields, Consumer<Result> completion) throws IOException {
        if (closed) throw new IOException("连接已关闭");
        if (requests.size() >= 8) throw new IOException("待处理操作过多，请稍后重试");
        String id = "social-" + UUID.randomUUID();
        Message.Builder builder = Message.of(MessageType.SOCIAL_REQUEST).requestId(id).socialAction(action.name());
        fields.accept(builder);
        ScheduledFuture<?> timerTask = timer.schedule(() -> {
            synchronized (this) {
                complete(id, null, "等待服务端确认超时，请刷新后查看结果");
            }
        }, timeout, TimeUnit.MILLISECONDS);
        requests.put(id, new Pending(new ArrayList<>(), completion, timerTask));
        try {
            sender.send(builder.build());
        } catch (IOException ex) {
            requests.remove(id);
            timerTask.cancel(false);
            throw ex;
        }
    }

    synchronized boolean receive(Message message) {
        if (message.type() != MessageType.SOCIAL_ROW && message.type() != MessageType.SOCIAL_END
                && !(message.type() == MessageType.ERROR && message.requestId() != null && message.requestId().startsWith("social-"))) return false;
        String id = message.requestId();
        Pending pending = requests.get(id);
        if (pending == null) return true;
        if (message.type() == MessageType.SOCIAL_ROW) {
            SocialItem row = message.socialItem();
            if (row == null || row.kind() == null || row.id() == null || pending.rows.size() >= SocialAction.PAGE_SIZE)
                complete(id, null, "无效的社交数据");
            else pending.rows.add(row);
        } else if (message.type() == MessageType.SOCIAL_END) {
            String next = message.cursor();
            if (next != null && (pending.rows.size() != SocialAction.PAGE_SIZE
                    || !next.equals(pending.rows.get(pending.rows.size() - 1).key()))) {
                complete(id, null, "无效的社交数据游标");
            } else complete(id, next, null);
        } else complete(id, null, Objects.toString(message.message(), "操作失败"));
        return true;
    }

    private void complete(String id, String next, String error) {
        Pending pending = requests.remove(id);
        if (pending == null) return;
        pending.deadline.cancel(false);
        Result result = new Result(error == null ? List.copyOf(pending.rows) : List.of(), next, error);
        emit.accept(() -> { if (!closed) pending.completion.accept(result); });
    }

    @Override public synchronized void close() {
        closed = true;
        for (Pending pending : requests.values()) pending.deadline.cancel(false);
        requests.clear();
    }
}
