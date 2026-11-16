package cn.ltalk.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecentServersTest {
    @TempDir Path directory;

    @Test void recentEndpointsPersistInOrderWithoutDuplicatesAndRecoverFromCorruptPreferences() throws Exception {
        Path file = directory.resolve("recent-servers.properties");
        RecentServers store = new RecentServers(file);
        Files.writeString(file, "host.0=\\u12xx");
        assertEquals(List.of(), store.load());
        for (int i = 0; i < 8; i++) store.remember(new ClientSessions.Address("server-" + i + ".local", 9000 + i));
        ClientSessions.Address reused = new ClientSessions.Address("server-3.local", 9003);
        store.remember(reused);
        List<ClientSessions.Address> saved = new RecentServers(file).load();
        assertEquals(RecentServers.LIMIT, saved.size());
        assertEquals(reused, saved.get(0));
        assertEquals(1, saved.stream().filter(reused::equals).count());
        assertEquals("server-7.local", saved.get(1).host());
        assertFalse(saved.stream().anyMatch(address -> address.host().equals("server-0.local")));
    }
}
