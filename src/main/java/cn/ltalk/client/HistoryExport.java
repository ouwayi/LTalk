package cn.ltalk.client;

import cn.ltalk.common.ChatRecord;
import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.*;

/** Incremental UTF-8 export; a failed export never publishes a partial final file. */
public final class HistoryExport implements AutoCloseable {
    private final Path temporary, destination;
    private final BufferedWriter writer;
    private final boolean csv;
    private final boolean replace;
    private boolean first = true, closed;
    public HistoryExport(Path directory, String user, String format) throws IOException {
        this(directory.resolve(defaultName(user, format)), format, false);
    }
    public static String defaultName(String user, String format) throws IOException {
        user = cn.ltalk.common.Validation.userId(user);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        return user + "_" + stamp + "_" + UUID.randomUUID().toString().substring(0, 8) + "." + format;
    }
    /** Explicit destination; replacement is permitted only after the save dialog's confirmation. */
    public HistoryExport(Path destination, String format, boolean replace) throws IOException {
        if (!List.of("json", "csv").contains(format)) throw new IllegalArgumentException("导出格式须为 json 或 csv");
        csv = format.equals("csv");
        this.destination = destination.toAbsolutePath(); this.replace = replace;
        temporary = Files.createTempFile(this.destination.getParent(), ".ltalk-export-", ".tmp");
        try { writer = Files.newBufferedWriter(temporary); }
        catch (IOException ex) { Files.deleteIfExists(temporary); throw ex; }
        try { writer.write(csv ? "messageId,conversationId,sender,recipient,content,messageType,sentAt\r\n" : "["); }
        catch (IOException ex) { close(); throw ex; }
    }
    public synchronized void append(ChatRecord record) throws IOException {
        if (closed) throw new IOException("导出已结束");
        if (csv) {
            String[] values = {record.messageId(), record.conversationId(), record.sender(), record.recipient(),
                    record.content(), record.messageType(), record.sentAt()};
            for (int i = 0; i < values.length; i++) {
                if (i > 0) writer.write(',');
                writer.write('"'); writer.write(Objects.toString(values[i], "").replace("\"", "\"\"")); writer.write('"');
            }
            writer.write("\r\n");
        } else {
            if (!first) writer.write(','); writer.write('\n');
            writer.write(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(record));
        }
        first = false;
    }
    public synchronized Path finish() throws IOException {
        if (closed) throw new IOException("导出已结束");
        if (!csv) { if (!first) writer.write('\n'); writer.write(']'); }
        writer.close();
        if (replace) Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        else Files.move(temporary, destination); // Must fail if another process creates the destination meanwhile.
        closed = true; return destination;
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { writer.close(); } finally { Files.deleteIfExists(temporary); }
    }
}
