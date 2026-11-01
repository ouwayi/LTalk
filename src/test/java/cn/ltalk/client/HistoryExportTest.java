package cn.ltalk.client;

import cn.ltalk.common.ChatRecord;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoryExportTest {
    @TempDir Path directory;
    private final ChatRecord record = new ChatRecord("id", "GROUP:PUBLIC", "alice", null, "中文\n\"🙂", "GROUP_MESSAGE", "2026-11-01T00:00:00Z");
    @Test void jsonHasOneRecordPerPhysicalLineAndRemainsAValidArray() throws Exception {
        Path path = directory.resolve("自定义 名称.json");
        try (HistoryExport export = new HistoryExport(path, "json", false)) {
            export.append(record); export.append(record); assertFalse(Files.exists(path)); assertEquals(path, export.finish());
        }
        var lines = Files.readAllLines(path); assertEquals(4, lines.size()); assertEquals("[", lines.get(0)); assertEquals("]", lines.get(3));
        assertTrue(lines.get(1).endsWith(",")); assertTrue(lines.get(2).contains("\\n"));
        assertEquals(2, JsonParser.parseString(Files.readString(path)).getAsJsonArray().size());
    }
    @Test void replacementRequiresConsentAndFailureNeverTruncatesExistingDestination() throws Exception {
        Path path = directory.resolve("existing.json"); Files.writeString(path, "original");
        try (HistoryExport export = new HistoryExport(path, "json", false)) {
            export.append(record); assertThrows(FileAlreadyExistsException.class, export::finish); assertEquals("original", Files.readString(path));
        }
        try (HistoryExport export = new HistoryExport(path, "json", true)) { export.append(record); export.finish(); }
        assertEquals(1, JsonParser.parseString(Files.readString(path)).getAsJsonArray().size());
        try (HistoryExport cancelled = new HistoryExport(path, "csv", true)) { cancelled.append(record); }
        assertEquals(1, JsonParser.parseString(Files.readString(path)).getAsJsonArray().size());
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }
}
