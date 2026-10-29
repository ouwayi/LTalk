package cn.ltalk.server;

import cn.ltalk.common.MessageType;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Default short regression; -Dltalk.soak.seconds=600 runs the course's ten-minute observation. */
class LoadTest {
    @Test @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void tenClientsStayOnlineAndReceiveEveryGroupAndPrivateFrame() throws Exception {
        int seconds = Integer.getInteger("ltalk.soak.seconds", 2);
        assertTrue(seconds >= 1 && seconds <= 780);
        List<Peer> peers = new ArrayList<>();
        long started = System.nanoTime();
        long heapBefore = usedHeap();
        int baseline = workerThreads();
        int peakThreads = baseline;
        long peakHeap = heapBefore;
        long maxRoundTripMillis = 0;
        int rounds = 0;
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); cn.ltalk.support.Peer.seed(server);
            try {
                for (int i = 0; i < 10; i++) peers.add(Peer.login(server.port(), "load" + i));
                for (Peer peer : peers) peer.users(10);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                do {
                    long tick = System.nanoTime();
                    int sender = rounds % 10;
                    String body = "LOAD-" + rounds + " 中文🙂";
                    peers.get(sender).group(body);
                    for (Peer peer : peers) assertEquals(body, peer.type(MessageType.GROUP_MESSAGE).content());
                    int receiver = (sender + 1) % 10;
                    peers.get(sender).privateTo("load" + receiver, "PRIVATE-" + rounds);
                    assertEquals("PRIVATE-" + rounds, peers.get(sender).type(MessageType.PRIVATE_MESSAGE).content());
                    assertEquals("PRIVATE-" + rounds, peers.get(receiver).type(MessageType.PRIVATE_MESSAGE).content());
                    assertEquals(10, server.onlineCount());
                    maxRoundTripMillis = Math.max(maxRoundTripMillis, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tick));
                    peakThreads = Math.max(peakThreads, workerThreads());
                    peakHeap = Math.max(peakHeap, usedHeap());
                    rounds++;
                    long remainingTick = TimeUnit.SECONDS.toNanos(1) - (System.nanoTime() - tick);
                    if (remainingTick > 0) TimeUnit.NANOSECONDS.sleep(remainingTick);
                } while (System.nanoTime() < deadline);
            } finally { for (Peer peer : peers) peer.close(); }
        }
        assertEquals(baseline, workerThreads(), "Server worker threads must end on shutdown");
        // Each peer's complete captured frames include no third-party private message.
        for (int i = 0; i < 10; i++) {
            final String identity = "load" + i;
            for (var frame : peers.get(i).received) {
                if (frame.type() == MessageType.PRIVATE_MESSAGE)
                    assertTrue(identity.equals(frame.from()) || identity.equals(frame.to()));
            }
        }
        System.out.printf(Locale.ROOT,
                "LOAD_RESULT clients=10 rounds=%d groupDeliveries=%d privateDeliveries=%d elapsedSeconds=%.2f maxRoundTripMillis=%d baselineThreads=%d peakThreads=%d finalThreads=%d heapBeforeMiB=%.2f peakHeapMiB=%.2f heapAfterMiB=%.2f%n",
                rounds, rounds * 10, rounds * 2, (System.nanoTime() - started) / 1e9, maxRoundTripMillis,
                baseline, peakThreads, workerThreads(), heapBefore / 1048576.0, peakHeap / 1048576.0, usedHeap() / 1048576.0);
    }
    private static int workerThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().startsWith("ltalk-")).count();
    }
    private static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
}
