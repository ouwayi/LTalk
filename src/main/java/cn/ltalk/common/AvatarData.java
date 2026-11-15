package cn.ltalk.common;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;

/** Bounded square avatar payload. Empty means generated initials. */
public final class AvatarData {
    public static final int SIZE = 144;
    private static final int LEGACY_SIZE = 48;
    public static final int MAX_BYTES = 10_240;
    private AvatarData() { }
    public static BufferedImage decode(String value) throws ProtocolException {
        if (value == null || value.isEmpty()) return null;
        try {
            if (value.length() > ((MAX_BYTES + 2) / 3) * 4) throw new IOException();
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length > MAX_BYTES) throw new IOException();
            try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException();
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    String format = reader.getFormatName();
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    boolean png = "png".equalsIgnoreCase(format), jpeg = "jpeg".equalsIgnoreCase(format);
                    if (width != height || !((width == SIZE && (png || jpeg)) || (width == LEGACY_SIZE && png))) throw new IOException();
                    BufferedImage image = reader.read(0);
                    if (image == null) throw new IOException();
                    return image;
                } finally { reader.dispose(); }
            }
        } catch (IOException | RuntimeException ex) {
            throw new ProtocolException("INVALID_AVATAR", "头像须为 144×144 PNG/JPEG 或旧版 48×48 PNG，大小不超过 10 KiB");
        }
    }
}
