package cn.ltalk.client;

import cn.ltalk.common.*;
import cn.ltalk.server.ChatServer;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DiscoveryIntegrationTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test void enabledResponderAdvertisesActualTcpPortAndStopsWithServer() throws Exception {
        int udpPort;
        try (DatagramSocket probe = new DatagramSocket(0, LOOPBACK)) { udpPort = probe.getLocalPort(); }
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.enableDiscovery(udpPort, "测试局域网");
            server.start();
            assertEquals(List.of(new DiscoveryClient.Candidate("测试局域网", new ClientSessions.Address("127.0.0.1", server.port()))),
                    DiscoveryClient.discover(List.of(LOOPBACK), udpPort, 300));
            assertHello(server.port());
        }
        try (DatagramSocket probe = new DatagramSocket(udpPort, LOOPBACK)) {
            assertEquals(List.of(), DiscoveryClient.discover(List.of(LOOPBACK), probe.getLocalPort(), 150));
        }
    }

    @Test void occupiedDiscoveryPortDoesNotPreventManualTcpConnections() throws Exception {
        try (DatagramSocket udp = new DatagramSocket(0, LOOPBACK); ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.enableDiscovery(udp.getLocalPort(), "LTalk");
            server.start();
            assertHello(server.port());
            assertEquals(List.of(), DiscoveryClient.discover(List.of(LOOPBACK), udp.getLocalPort(), 150));
        }
    }

    @Test void distinctServersAreListedOnceAndUnrelatedDatagramsAreIgnored() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (DatagramSocket udp = new DatagramSocket(0, LOOPBACK)) {
            udp.setSoTimeout(2000);
            Future<?> sending = worker.submit(() -> {
                byte[] buffer = new byte[LanDiscovery.MAX_PACKET + 1];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                udp.receive(packet);
                String nonce = LanDiscovery.requestNonce(packet);
                assertNotNull(nonce);
                String alpha = LanDiscovery.randomId(), beta = LanDiscovery.randomId();
                byte[] oversized = Arrays.copyOf(LanDiscovery.offer(nonce, alpha, "Ignored", 1234), LanDiscovery.MAX_PACKET + 1);
                for (byte[] reply : List.of(LanDiscovery.offer("0".repeat(32), alpha, "Unrelated", 1234), oversized,
                        LanDiscovery.offer(nonce, alpha, "Invalid", 0), LanDiscovery.offer(nonce, alpha, "Alpha", 9010),
                        LanDiscovery.offer(nonce, alpha, "Alpha", 9010), LanDiscovery.offer(nonce, beta, "Beta", 9011))) {
                    udp.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress()));
                }
                return null;
            });
            List<DiscoveryClient.Candidate> candidates = DiscoveryClient.discover(List.of(LOOPBACK), udp.getLocalPort(), 400);
            sending.get(2, TimeUnit.SECONDS);
            assertEquals(List.of(new DiscoveryClient.Candidate("Alpha", new ClientSessions.Address("127.0.0.1", 9010)),
                    new DiscoveryClient.Candidate("Beta", new ClientSessions.Address("127.0.0.1", 9011))), candidates);
        } finally { worker.shutdownNow(); }
    }

    private static void assertHello(int port) throws Exception {
        try (Peer peer = new Peer(port)) {
            peer.send(Message.of(MessageType.HELLO).requestId("discover-test").build());
            assertEquals("discover-test", peer.type(MessageType.HELLO_OK).requestId());
        }
    }
}
