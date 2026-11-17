package cn.ltalk.server;

import java.io.*;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** One FIFO per socket. The writer performs I/O outside the queue monitor. */
final class SessionOutput {
    private static final int MAX_FRAMES = 128;
    private static final int MAX_BYTES = 512 * 1024;
    private record Frame(byte[] bytes, CompletableFuture<Void> written) { }
    private final ArrayDeque<Frame> queue = new ArrayDeque<>();
    private int bytes;
    private boolean closed;
    private Thread writer;

    synchronized boolean offer(byte[] payload, CompletableFuture<Void> written) {
        if (closed || queue.size() == MAX_FRAMES || bytes + payload.length > MAX_BYTES) return false;
        queue.addLast(new Frame(payload, written));
        bytes += payload.length;
        notifyAll();
        return true;
    }

    synchronized void start(OutputStream output, Consumer<IOException> failed) {
        if (closed) return;
        writer = new Thread(() -> writeLoop(output, failed), "ltalk-session-write");
        writer.setDaemon(true);
        writer.start();
    }

    private void writeLoop(OutputStream output, Consumer<IOException> failed) {
        try {
            Frame frame;
            while ((frame = take()) != null) {
                try {
                    output.write(frame.bytes());
                    output.flush();
                    if (frame.written() != null) frame.written().complete(null);
                } catch (IOException ex) {
                    if (frame.written() != null) frame.written().completeExceptionally(ex);
                    throw ex;
                }
            }
        } catch (IOException ex) { failed.accept(ex); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private synchronized Frame take() throws InterruptedException {
        while (!closed && queue.isEmpty()) wait();
        if (closed) return null;
        Frame frame = queue.removeFirst();
        bytes -= frame.bytes().length;
        return frame;
    }

    synchronized void close() {
        closed = true;
        for (Frame frame : queue) {
            if (frame.written() != null) frame.written().completeExceptionally(new IOException("连接已关闭"));
        }
        queue.clear();
        bytes = 0;
        notifyAll();
    }

    void awaitTermination() throws InterruptedException {
        Thread active;
        synchronized (this) { active = writer; }
        if (active != null) active.join(1000);
    }
}
