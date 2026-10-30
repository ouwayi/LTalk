package cn.ltalk.common;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;

/** Small PNG payload, bounded before image decoding. Empty means generated initials. */
public final class AvatarData {
    public static final int SIZE = 48;
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
                    if (!"png".equalsIgnoreCase(reader.getFormatName()) || reader.getWidth(0) != SIZE || reader.getHeight(0) != SIZE)
                        throw new IOException();
                    BufferedImage image = reader.read(0);
                    if (image == null) throw new IOException();
                    return image;
                } finally { reader.dispose(); }
            }
        } catch (IOException | RuntimeException ex) { throw new ProtocolException("INVALID_AVATAR", "头像须为 48×48 PNG，大小不超过 10 KiB"); }
    }
}
