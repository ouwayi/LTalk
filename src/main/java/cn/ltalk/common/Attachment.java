package cn.ltalk.common;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.function.LongConsumer;

/** Metadata carried by the chat protocol. The original bytes travel on a separate socket. */
public record Attachment(Kind kind, String name, long size, String thumbnail) {
    public enum Kind { IMAGE, FILE }
    public static final long IMAGE_LIMIT = 12L * 1024 * 1024;
    public static final long FILE_LIMIT = 50L * 1024 * 1024;
    public static final int THUMBNAIL_LIMIT = 6000;
    public static final int IDLE_MILLIS = 15_000;
    public static final int TRANSFER_SECONDS = 120;

    public String messageContent() { return kind == Kind.IMAGE ? "[图片]" : name; }

    public Attachment withoutOriginalImageName() {
        if (kind != Kind.IMAGE) return this;
        String suffix = name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        return new Attachment(kind, "图片" + suffix, size, thumbnail);
    }

    public void validateUpload() throws ProtocolException {
        if (kind == null || name == null || !name.equals(safeName(name)) || thumbnail != null || size < 0)
            throw new ProtocolException("BAD_ATTACHMENT", "无效的文件信息");
        if (size > (kind == Kind.IMAGE ? IMAGE_LIMIT : FILE_LIMIT))
            throw new ProtocolException("ATTACHMENT_TOO_LARGE", kind == Kind.IMAGE ? "图片最多 12 MiB" : "附件最多 50 MiB");
        if (kind == Kind.IMAGE && (size == 0 || !isImageName(name)))
            throw new ProtocolException("INVALID_IMAGE", "请选择 PNG、JPEG、GIF 或 BMP 图片");
    }

    public static boolean isImageName(String name) {
        return name.toLowerCase(Locale.ROOT).matches("(?s).+\\.(png|jpe?g|gif|bmp)");
    }

    public static String safeName(String original) {
        String safe = original.replaceAll("[\\p{Cc}\\p{Cf}<>:\"/\\\\|?*]", "_")
                .strip().replaceAll("[. ]+$", "");
        if (safe.isEmpty()) return "附件";
        if (safe.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]) *(\\..*)?")) safe = "_" + safe;
        if (safe.codePointCount(0, safe.length()) <= 160) return safe;
        int dot = safe.lastIndexOf('.');
        String suffix = dot >= 0 && safe.length() - dot <= 16 ? safe.substring(dot) : "";
        return safe.substring(0, safe.offsetByCodePoints(0, 160 - suffix.codePointCount(0, suffix.length())))
                .replaceAll("[. ]+$", "") + suffix;
    }

    public static void copy(InputStream input, OutputStream output, long size, LongConsumer progress) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("文件传输在完成前中断");
            output.write(buffer, 0, count);
            remaining -= count;
            progress.accept(size - remaining);
        }
        output.flush();
    }
}
