package cn.ltalk.server;

import cn.ltalk.common.LanDiscovery;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.logging.*;

/** A local-link UDP responder with the same lifetime as the TCP server. */
final class DiscoveryResponder implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(DiscoveryResponder.class.getName());
    private final DatagramSocket listener, sender;
    private final InetAddress tcpBind;
    private final List<InterfaceAddress> links;
    private final String instanceId = LanDiscovery.randomId();
    private final String name;
    private final int tcpPort;
    private final Thread worker;

    DiscoveryResponder(InetAddress tcpBind, int tcpPort, int discoveryPort, String name) throws IOException {
        if (!(tcpBind instanceof Inet4Address)) throw new IOException("局域网发现需要 IPv4 监听地址");
        this.tcpBind = tcpBind;
        this.tcpPort = tcpPort;
        this.name = name;
        links = LanDiscovery.localIpv4Links().stream()
                .filter(link -> tcpBind.isAnyLocalAddress() || tcpBind.equals(link.getAddress())).toList();
        boolean wildcard = tcpBind.isAnyLocalAddress();
        // Receive broadcast on the wildcard address, and send from the advertised TCP interface.
        InetSocketAddress endpoint = tcpBind.isLoopbackAddress() ? new InetSocketAddress(tcpBind, discoveryPort)
                : new InetSocketAddress("0.0.0.0", discoveryPort);
        listener = new DatagramSocket(endpoint);
        try {
            sender = wildcard || tcpBind.isLoopbackAddress() ? listener
                    : new DatagramSocket(new InetSocketAddress(tcpBind, 0));
        } catch (SocketException ex) {
            listener.close();
            throw ex;
        }
        worker = new Thread(this::respond, "ltalk-discovery");
        worker.setDaemon(true);
        worker.start();
    }

    private void respond() {
        byte[] buffer = new byte[LanDiscovery.MAX_PACKET + 1];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (!listener.isClosed()) {
            try {
                packet.setLength(buffer.length);
                listener.receive(packet);
            } catch (IOException ex) {
                if (!listener.isClosed()) LOG.log(Level.WARNING, "局域网发现监听中断", ex);
                close();
                return;
            }
            String nonce = LanDiscovery.requestNonce(packet);
            InetAddress source = packet.getAddress();
            if (nonce == null || packet.getPort() == 0 || !local(source)) continue;
            byte[] reply = LanDiscovery.offer(nonce, instanceId, name, tcpPort);
            try { sender.send(new DatagramPacket(reply, reply.length, packet.getSocketAddress())); }
            catch (IOException ex) {
                if (!listener.isClosed()) LOG.log(Level.FINE, "局域网发现回复无法发送", ex);
            }
        }
    }

    private boolean local(InetAddress source) {
        if (source instanceof Inet4Address && source.isLoopbackAddress())
            return tcpBind.isAnyLocalAddress() || tcpBind.isLoopbackAddress();
        return links.stream().anyMatch(link -> LanDiscovery.onLink(source, link));
    }

    @Override public void close() {
        listener.close();
        if (sender != listener) sender.close();
    }

    void awaitTermination() throws InterruptedException { worker.join(1000); }
}
