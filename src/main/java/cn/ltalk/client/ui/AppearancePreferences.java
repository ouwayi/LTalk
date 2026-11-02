package cn.ltalk.client.ui;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** Local appearance only; no chat, user identity or server configuration is persisted here. */
public final class AppearancePreferences {
    private final Path file;
    public AppearancePreferences(Path file) { this.file = file.toAbsolutePath(); }
    public static Path defaultFile() { return Path.of(System.getProperty("user.home"), ".ltalk", "appearance.properties"); }
    public AppearanceSettings load() {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 32 * 1024) return AppearanceSettings.defaults();
            Properties p = new Properties();
            try (InputStream input = Files.newInputStream(file)) { p.load(input); }
            String version = p.getProperty("version");
            if (!"1".equals(version) && !"2".equals(version)) return AppearanceSettings.defaults();
            AppearanceSettings settings = new AppearanceSettings(AppearanceSettings.Mode.valueOf(p.getProperty("mode")),
                    AppearanceSettings.BackgroundStyle.valueOf(p.getProperty("background")),
                    AppearanceSettings.color(p.getProperty("light.start")), AppearanceSettings.color(p.getProperty("light.end")),
                    AppearanceSettings.color(p.getProperty("dark.start")), AppearanceSettings.color(p.getProperty("dark.end")));
            // Only the legacy default pair changes; all other user colors remain intact.
            if ("1".equals(version) && settings.darkStart().equals(AppearanceSettings.color("#111B27"))
                    && settings.darkEnd().equals(AppearanceSettings.color("#213347"))) {
                AppearanceSettings defaults = AppearanceSettings.defaults();
                settings = new AppearanceSettings(settings.mode(), settings.style(), settings.lightStart(), settings.lightEnd(),
                        defaults.darkStart(), defaults.darkEnd());
            }
            return settings;
        } catch (IOException | RuntimeException ex) { return AppearanceSettings.defaults(); }
    }
    public void save(AppearanceSettings settings) throws IOException {
        Properties p = new Properties(); p.setProperty("version", "2");
        p.setProperty("mode", settings.mode().name()); p.setProperty("background", settings.style().name());
        p.setProperty("light.start", AppearanceSettings.hex(settings.lightStart())); p.setProperty("light.end", AppearanceSettings.hex(settings.lightEnd()));
        p.setProperty("dark.start", AppearanceSettings.hex(settings.darkStart())); p.setProperty("dark.end", AppearanceSettings.hex(settings.darkEnd()));
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "appearance-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { p.store(output, "LTalk appearance"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
