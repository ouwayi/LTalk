package cn.ltalk.common;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** The IPv4 UDP discovery datagrams are separate from the TCP chat protocol. */
public final class LanDiscovery {
    public static final int DEFAULT_PORT = 9001;
    public static final int MAX_PACKET = 256;
    private static final String REQUEST = "LTALK-DISCOVER/1 ";
    private static final String OFFER = "LTALK-OFFER/1 ";

    private LanDiscovery() { }

    public record Offer(String instanceId, String name, int port) { }

    public static int configuredPort() {
        int port = Integer.parseInt(System.getProperty("ltalk.discovery.port", Integer.toString(DEFAULT_PORT)));
        if (!validPort(port)) throw new IllegalArgumentException("发现端口必须为 1～65535");
        return port;
    }

    public static boolean validPort(int port) { return port >= 1 && port <= 65535; }

    public static String randomId() { return UUID.randomUUID().toString().replace("-", ""); }

    public static byte[] request(String nonce) {
        return ascii(REQUEST + nonce);
    }

    public static String requestNonce(DatagramPacket packet) {
        String value = read(packet);
        if (!value.startsWith(REQUEST)) return null;
        String nonce = value.substring(REQUEST.length());
        return nonce.matches("[0-9a-f]{32}") ? nonce : null;
    }

    public static byte[] offer(String nonce, String instanceId, String name, int port) {
        return ascii(OFFER + nonce + " " + instanceId + " " + port + " "
                + Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8)));
    }

    public static Offer parseOffer(DatagramPacket packet, String nonce) {
        String value = read(packet);
        String prefix = OFFER + nonce + " ";
        if (!value.startsWith(prefix)) return null;
        String[] fields = value.substring(prefix.length()).split(" ", -1);
        if (fields.length != 3 || !fields[0].matches("[0-9a-f]{32}") || !fields[1].matches("[0-9]{1,5}")) return null;
        try {
            int port = Integer.parseInt(fields[1]);
            if (!validPort(port)) return null;
            byte[] encoded = Base64.getUrlDecoder().decode(fields[2]);
            String name = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded)).toString();
            return new Offer(fields[0], validateName(name), port);
        } catch (IllegalArgumentException | CharacterCodingException ex) {
            return null;
        }
    }

    public static String validateName(String name) {
        String value = name.strip();
        if (value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 96
                || value.codePoints().anyMatch(cp -> Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT))
            throw new IllegalArgumentException("服务器名称须为 1～96 个 UTF-8 字节，且不含控制字符");
        return value;
    }

    public static List<InterfaceAddress> localIpv4Links() throws SocketException {
        List<InterfaceAddress> links = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return links;
        while (interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            for (InterfaceAddress link : network.getInterfaceAddresses()) {
                if (link.getAddress() instanceof Inet4Address && link.getBroadcast() != null) links.add(link);
            }
        }
        return links;
    }

    public static boolean onLink(InetAddress source, InterfaceAddress link) {
        int prefix = link.getNetworkPrefixLength();
        if (!(source instanceof Inet4Address) || source.isAnyLocalAddress() || source.isMulticastAddress()
                || source.equals(link.getBroadcast()) || prefix <= 0 || prefix > 32) return false;
        int mask = -1 << (32 - prefix);
        int subnet = ByteBuffer.wrap(link.getAddress().getAddress()).getInt() & mask;
        int address = ByteBuffer.wrap(source.getAddress()).getInt();
        return (address & mask) == subnet && (prefix >= 31 || address != subnet);
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static String read(DatagramPacket packet) {
        if (packet.getLength() > MAX_PACKET) return "";
        return new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.US_ASCII);
    }
}
