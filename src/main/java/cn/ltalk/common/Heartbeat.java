package cn.ltalk.common;

import java.util.concurrent.*;
import java.util.function.Consumer;

/** One outstanding probe per connection. The sender must enqueue writes without blocking. */
public final class Heartbeat implements AutoCloseable {
    private final HeartbeatSettings settings;
    private final Consumer<Message> sender;
    private final Runnable timedOut;
    private final ScheduledThreadPoolExecutor timer;
    private boolean started;
    private boolean closed;
    private long sequence;
    private String pending;
    private ScheduledFuture<?> deadline;

    public Heartbeat(HeartbeatSettings settings, Consumer<Message> sender, Runnable timedOut) {
        this.settings = settings;
        this.sender = sender;
        this.timedOut = timedOut;
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "ltalk-heartbeat-watch");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
    }

    public synchronized void start() {
        if (closed || started) return;
        started = true;
        scheduleProbe();
    }

    private void scheduleProbe() {
        timer.schedule(this::probe, settings.intervalMillis(), TimeUnit.MILLISECONDS);
    }

    private void probe() {
        Message ping;
        synchronized (this) {
            if (closed) return;
            pending = "h" + ++sequence;
            String id = pending;
            // Queueing and blocked writes count toward the deadline; the watchdog never writes.
            deadline = timer.schedule(() -> expire(id), settings.timeoutMillis(), TimeUnit.MILLISECONDS);
            ping = Message.of(MessageType.PING).requestId(id).build();
        }
        sender.accept(ping);
    }

    public void receive(Message message) throws ProtocolException {
        Validation.requestId(message.requestId());
        if (message.type() == MessageType.PING) {
            synchronized (this) { if (closed) return; }
            sender.accept(Message.of(MessageType.PONG).requestId(message.requestId()).build());
        } else if (message.type() == MessageType.PONG) {
            synchronized (this) {
                if (closed || !message.requestId().equals(pending)) return;
                pending = null;
                deadline.cancel(false);
                scheduleProbe();
            }
        } else throw new IllegalArgumentException("只接受 PING/PONG");
    }

    private void expire(String id) {
        synchronized (this) {
            if (closed || !id.equals(pending)) return;
            close();
        }
        timedOut.run();
    }

    @Override public synchronized void close() {
        closed = true;
        timer.shutdownNow();
    }

    public void awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        timer.awaitTermination(timeout, unit);
    }
}
