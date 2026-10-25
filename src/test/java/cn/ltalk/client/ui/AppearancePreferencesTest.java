package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AppearancePreferencesTest {
    @TempDir Path directory;
    @Test void customColorsAndModeSurviveReloadWithoutDiscardingOtherMode() throws Exception {
        Path file = directory.resolve("settings/appearance.properties"); AppearancePreferences preferences = new AppearancePreferences(file);
        assertEquals(AppearanceSettings.defaults(), preferences.load());
        var value = AppearanceSettings.defaults().withBackground(AppearanceSettings.BackgroundStyle.SOLID,
                AppearanceSettings.color("#ABCDEF"), AppearanceSettings.color("#123456"))
                .withMode(AppearanceSettings.Mode.DARK).withBackground(AppearanceSettings.BackgroundStyle.GRADIENT,
                        AppearanceSettings.color("#213245"), AppearanceSettings.color("#112233"));
        preferences.save(value); assertEquals(value, new AppearancePreferences(file).load());
        assertEquals(AppearanceSettings.color("#ABCDEF"), preferences.load().withMode(AppearanceSettings.Mode.LIGHT).start());
    }
    @Test void malformedUnknownOrOversizedSettingsFallBackWithoutRewritingFile() throws Exception {
        Path file = directory.resolve("appearance.properties"); AppearancePreferences preferences = new AppearancePreferences(file);
        for (String text : new String[]{"broken", "version=9\nmode=DARK", "version=1\nmode=BAD", "x".repeat(32769)}) {
            Files.writeString(file, text); assertEquals(AppearanceSettings.defaults(), preferences.load()); assertEquals(text, Files.readString(file));
        }
    }
    @Test void failedSaveLeavesTargetAndNoTemporaryFiles() throws Exception {
        Path target = Files.createDirectory(directory.resolve("appearance.properties"));
        assertThrows(IOException.class, () -> new AppearancePreferences(target).save(AppearanceSettings.defaults()));
        assertTrue(Files.isDirectory(target));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }
}
