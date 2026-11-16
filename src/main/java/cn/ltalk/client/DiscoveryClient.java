package cn.ltalk.client;

import cn.ltalk.common.LanDiscovery;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class DiscoveryClient {
    private static final int MAX_RESULTS = 32;
    private static final int RECEIVE_SLICE_MILLIS = 100;

    record Candidate(String name, ClientSessions.Address address) { }

    private DiscoveryClient() { }

    static List<Candidate> discover() throws IOException {
        Set<InetAddress> destinations = new LinkedHashSet<>();
        destinations.add(InetAddress.getByName("127.0.0.1"));
        for (InterfaceAddress link : LanDiscovery.localIpv4Links()) destinations.add(link.getBroadcast());
        return discover(destinations, LanDiscovery.configuredPort(), 2000);
    }

    static List<Candidate> discover(Collection<InetAddress> destinations, int port, int timeoutMillis) throws IOException {
        if (Thread.currentThread().isInterrupted()) return List.of();
        String nonce = LanDiscovery.randomId();
        byte[] request = LanDiscovery.request(nonce);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            boolean sent = false;
            IOException sendError = null;
            for (InetAddress address : destinations) {
                if (Thread.currentThread().isInterrupted()) return List.of();
                try {
                    socket.send(new DatagramPacket(request, request.length, address, port));
                    sent = true;
                } catch (IOException ex) {
                    sendError = ex;
                }
            }
            if (!sent && sendError != null) throw sendError;
            byte[] buffer = new byte[LanDiscovery.MAX_PACKET + 1];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (!Thread.currentThread().isInterrupted() && candidates.size() < MAX_RESULTS) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                socket.setSoTimeout((int) Math.min(RECEIVE_SLICE_MILLIS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
                packet.setLength(buffer.length);
                try { socket.receive(packet); }
                catch (SocketTimeoutException ex) { continue; }
                if (!(packet.getAddress() instanceof Inet4Address)) continue;
                LanDiscovery.Offer offer = LanDiscovery.parseOffer(packet, nonce);
                if (offer == null) continue;
                ClientSessions.Address address = new ClientSessions.Address(packet.getAddress().getHostAddress(), offer.port());
                Candidate candidate = new Candidate(offer.name(), address);
                if (packet.getAddress().isLoopbackAddress()) candidates.put(offer.instanceId(), candidate);
                else candidates.putIfAbsent(offer.instanceId(), candidate);
            }
        }
        return candidates.values().stream().sorted(Comparator.comparing(Candidate::name)
                .thenComparing(candidate -> candidate.address().host()).thenComparingInt(candidate -> candidate.address().port())).toList();
    }
}
