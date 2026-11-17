package cn.ltalk.client;

import cn.ltalk.common.LanDiscovery;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** A small, per-OS-user list of successful TCP handshakes. */
final class RecentServers {
    static final int LIMIT = 6;
    private final Path file;

    RecentServers(Path file) { this.file = file.toAbsolutePath(); }

    List<ClientSessions.Address> load() {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 8192) return List.of();
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(file)) { properties.load(input); }
            Set<ClientSessions.Address> addresses = new LinkedHashSet<>();
            for (int i = 0; i < LIMIT; i++) {
                String host = properties.getProperty("host." + i, "");
                int port;
                try { port = Integer.parseInt(properties.getProperty("port." + i, "")); }
                catch (NumberFormatException ex) { continue; }
                if (validHost(host) && LanDiscovery.validPort(port)) addresses.add(new ClientSessions.Address(host, port,
                        Boolean.parseBoolean(properties.getProperty("tls." + i, "false"))));
            }
            return List.copyOf(addresses);
        } catch (IOException | IllegalArgumentException ex) {
            return List.of();
        }
    }

    void remember(ClientSessions.Address address) throws IOException {
        if (!validHost(address.host()) || !LanDiscovery.validPort(address.port())) return;
        Files.createDirectories(file.getParent());
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        // Separate client processes may share the same preferences directory.
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            List<ClientSessions.Address> previous = load();
            if (!previous.isEmpty() && previous.get(0).equals(address)) return;
            List<ClientSessions.Address> updated = new ArrayList<>();
            updated.add(address);
            for (ClientSessions.Address item : previous) {
                if (!item.equals(address) && updated.size() < LIMIT) updated.add(item);
            }
            Properties properties = new Properties();
            for (int i = 0; i < updated.size(); i++) {
                properties.setProperty("host." + i, updated.get(i).host());
                properties.setProperty("port." + i, Integer.toString(updated.get(i).port()));
                properties.setProperty("tls." + i, Boolean.toString(updated.get(i).tls()));
            }
            Path temporary = Files.createTempFile(file.getParent(), "recent-servers-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) { properties.store(output, "LTalk recent servers"); }
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temporary); }
        }
    }

    private static boolean validHost(String host) {
        return !host.isBlank() && host.length() <= 255
                && host.codePoints().noneMatch(cp -> Character.isWhitespace(cp) || Character.isISOControl(cp));
    }
}
