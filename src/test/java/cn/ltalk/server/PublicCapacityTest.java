package cn.ltalk.server;

import cn.ltalk.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class PublicCapacityTest {
    @TempDir Path directory;
    private static final int CLIENTS = 100;
    private static final String PASSWORD = "load-test-only";

    @Test @Timeout(value = 16, unit = TimeUnit.MINUTES)
    void oneHundredTlsClientsHandleBurstReconnectAndSlowReader() throws Exception {
        int seconds = Integer.getInteger("ltalk.capacity.seconds", 4);
        assertTrue(seconds >= 1 && seconds <= 780);
        var identity = new TlsTestIdentity(directory);
        var logins = Executors.newFixedThreadPool(12);
        List<Probe> peers = new ArrayList<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0, 140, Duration.ofSeconds(12),
                new HeartbeatSettings(2000, 15000))) {
            server.dataDirectory(directory.resolve("data"));
            server.publicAccess(new PublicAccess(identity.server, 24, 300, 300, 4, 8, 4, 60));
            server.start();
            // Prepare fixture accounts outside the measurement. The live login wave uses real PBKDF2 verification.
            for (int i = 0; i <= CLIENTS; i++) server.accounts().register(user(i), user(i), PASSWORD, false);
            SocialFixtures.permitPrivate(server, user(0), user(CLIENTS));
            long loginStart = System.nanoTime();
            for (int start = 0; start < CLIENTS; start += 20)
                peers.addAll(loginWave(logins, identity, server.port(), start, Math.min(CLIENTS, start + 20)));
            until(() -> server.onlineCount() == CLIENTS);
            long loginMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loginStart);

            Set<String> burst = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                String body = "burst-" + i;
                burst.add(body);
                peers.get(i % 4).group(body);
            }
            for (Probe peer : peers) {
                Set<String> received = new HashSet<>();
                for (int i = 0; i < 20; i++) assertTrue(received.add(peer.groupMessage()));
                assertEquals(burst, received);
            }

            for (int i = 0; i < 10; i++) peers.get(i).close();
            until(() -> server.onlineCount() == CLIENTS - 10);
            long reconnectStart = System.nanoTime();
            List<Probe> reconnected = loginWave(logins, identity, server.port(), 0, 10);
            for (int i = 0; i < 10; i++) peers.set(i, reconnected.get(i));
            until(() -> server.onlineCount() == CLIENTS);
            long reconnectMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reconnectStart);

            try (Probe slow = new Probe(identity, server.port(), user(CLIENTS), true)) {
                until(() -> server.onlineCount() == CLIENTS + 1);
                Probe sender = peers.get(0);
                Future<?> pressure = logins.submit(() -> {
                    for (int i = 0; i < 1500 && server.slowClientCount() == 0; i++) {
                        sender.send(Message.of(MessageType.PRIVATE_SEND).requestId("private-" + i)
                                .to(user(CLIENTS)).content("压".repeat(2000)).build());
                        Message result = sender.privateResults.poll(5, TimeUnit.SECONDS);
                        assertNotNull(result, "A slow recipient must not block its sender");
                        if (result.type() == MessageType.ERROR) {
                            assertTrue(Set.of("DELIVERY_UNCONFIRMED", "USER_OFFLINE").contains(result.code()));
                        }
                    }
                    return null;
                });
                peers.get(1).group("healthy-during-pressure");
                for (Probe peer : peers) assertEquals("healthy-during-pressure", peer.groupMessage());
                pressure.get(25, TimeUnit.SECONDS);
                assertEquals(1, server.slowClientCount(), "Only the non-reading client must be evicted");
                until(() -> server.onlineCount() == CLIENTS);
            }

            List<Long> latency = new ArrayList<>();
            long testStart = System.nanoTime(), cpuStart = cpuNanos();
            long deadline = testStart + TimeUnit.SECONDS.toNanos(seconds);
            long peakHeap = heapMiB();
            int peakServerThreads = 0;
            while (System.nanoTime() < deadline) {
                long start = System.nanoTime();
                String body = "capacity-" + latency.size();
                peers.get(latency.size() % CLIENTS).group(body);
                for (Probe peer : peers) assertEquals(body, peer.groupMessage());
                latency.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start));
                assertEquals(CLIENTS, server.onlineCount());
                if (latency.size() % 5 == 1) {
                    peakServerThreads = Math.max(peakServerThreads, serverThreads());
                    peakHeap = Math.max(peakHeap, heapMiB());
                }
                long sleep = TimeUnit.MILLISECONDS.toNanos(200) - (System.nanoTime() - start);
                if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep);
            }
            long duration = System.nanoTime() - testStart;
            long cpuUsed = cpuStart < 0 ? -1 : cpuNanos() - cpuStart;
            Collections.sort(latency);
            int p95 = (int) Math.ceil(latency.size() * .95) - 1;
            int pongs = peers.stream().mapToInt(peer -> peer.pongs).sum();
            if (seconds >= 4) assertTrue(pongs >= CLIENTS);
            assertEquals(CLIENTS, server.onlineCount());
            long archive = Files.size(directory.resolve("data/history/public.jsonl"));
            System.out.printf(Locale.ROOT, "PUBLIC_CAPACITY clients=%d elapsedSec=%.2f loginMs=%d reconnect10Ms=%d "
                            + "groupMessages=%d deliveries=%d p95Us=%d maxUs=%d heartbeatReplies=%d unexpectedDisconnects=0 "
                            + "slowEvictions=%d serverThreadsPeak=%d combinedHeapMiB=%d combinedCpuCorePercent=%.1f publicArchiveBytes=%d%n",
                    CLIENTS, duration / 1e9, loginMillis, reconnectMillis, latency.size(), latency.size() * CLIENTS,
                    latency.get(p95), latency.get(latency.size() - 1), pongs, server.slowClientCount(),
                    peakServerThreads, peakHeap, cpuUsed < 0 ? -1 : cpuUsed * 100.0 / duration, archive);
        } finally {
            for (Probe peer : peers) peer.close();
            logins.shutdownNow();
        }
    }

    private static List<Probe> loginWave(ExecutorService workers, TlsTestIdentity identity, int port, int start, int end) throws Exception {
        List<Callable<Probe>> wave = new ArrayList<>();
        for (int i = start; i < end; i++) {
            String user = user(i);
            wave.add(() -> new Probe(identity, port, user, false));
        }
        List<Probe> opened = new ArrayList<>();
        ExecutionException error = null;
        for (Future<Probe> result : workers.invokeAll(wave)) {
            try { opened.add(result.get()); }
            catch (ExecutionException ex) { if (error == null) error = ex; }
        }
        if (error != null) {
            for (Probe peer : opened) peer.close();
            throw error;
        }
        return opened;
    }

    private static String user(int index) { return "load" + index; }
    private static void until(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(15);
        assertTrue(condition.getAsBoolean());
    }
    private static long heapMiB() {
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
    }
    private static int serverThreads() {
        return (int) Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().startsWith("ltalk-")).count();
    }
    private static long cpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean cpu
                ? cpu.getProcessCpuTime() : -1;
    }

    private static final class Probe implements AutoCloseable {
        final Socket raw = new Socket();
        final Socket tls;
        final BlockingQueue<String> groups = new ArrayBlockingQueue<>(128);
        final BlockingQueue<Message> privateResults = new ArrayBlockingQueue<>(16);
        volatile IOException interrupted;
        volatile int pongs;
        int sent;

        Probe(TlsTestIdentity identity, int port, String user, boolean slow) throws IOException {
            try {
                if (slow) raw.setReceiveBufferSize(2048);
                raw.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                var secured = TlsTransport.layer(raw, identity.client, "localhost", true);
                tls = secured;
                tls.setSoTimeout(12000);
                secured.startHandshake();
                send(Message.of(MessageType.LOGIN).requestId("login").userId(user).password(PASSWORD).build());
                var reader = new MessageCodec.FrameReader(tls.getInputStream());
                Message result = reader.read();
                if (result == null || result.type() != MessageType.LOGIN_OK)
                    throw new IOException("登录 " + user + " 失败：" + (result == null ? "EOF" : result.code()));
                tls.setSoTimeout(0);
                if (!slow) {
                    Thread thread = new Thread(() -> read(reader), "capacity-probe");
                    thread.setDaemon(true);
                    thread.start();
                }
            } catch (IOException | RuntimeException ex) { close(); throw ex; }
        }

        private void read(MessageCodec.FrameReader reader) {
            try {
                Message message;
                while ((message = reader.read()) != null) {
                    switch (message.type()) {
                        case PING -> {
                            send(Message.of(MessageType.PONG).requestId(message.requestId()).build());
                            pongs++;
                        }
                        case GROUP_MESSAGE -> {
                            if (!groups.offer(message.content())) throw new IOException("负载探针群聊未及时消费");
                        }
                        case PRIVATE_MESSAGE, ERROR -> {
                            if (!privateResults.offer(message)) throw new IOException("负载探针私聊未及时消费");
                        }
                        default -> { }
                    }
                }
            } catch (IOException ex) { interrupted = ex; }
        }
        synchronized void send(Message message) throws IOException { MessageCodec.write(tls.getOutputStream(), message); }
        void group(String body) throws IOException {
            send(Message.of(MessageType.GROUP_SEND).requestId("group-" + ++sent).content(body).build());
        }
        String groupMessage() throws InterruptedException {
            String body = groups.poll(5, TimeUnit.SECONDS);
            assertNotNull(body, "Missing group frame, socket error=" + interrupted);
            return body;
        }
        @Override public void close() {
            try { raw.close(); } catch (IOException ignored) { }
        }
    }
}
