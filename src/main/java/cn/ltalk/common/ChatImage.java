package cn.ltalk.common;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

public final class ChatImage {
    private static final long PIXEL_LIMIT = 24_000_000;
    private static final Set<String> FORMATS = Set.of("PNG", "JPEG", "GIF", "BMP");
    private ChatImage() { }

    public static BufferedImage read(Path path, int edge) throws IOException {
        try (var input = new FileImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            ImageReader reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toUpperCase(Locale.ROOT))) throw invalidImage();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > PIXEL_LIMIT) throw invalidImage();
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, Math.max(width, height) / edge);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                return scale(reader.read(0, parameters), edge);
            } finally { reader.dispose(); }
        } catch (ProtocolException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw invalidImage();
        }
    }

    public static String thumbnail(Path path) throws IOException {
        BufferedImage image = read(path, 160);
        for (int edge : new int[]{160, 112, 80, 56}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(scale(image, edge), "jpeg", bytes);
            if (bytes.size() > 0 && bytes.size() <= Attachment.THUMBNAIL_LIMIT)
                return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
        throw invalidImage();
    }

    public static BufferedImage decodeThumbnail(String encoded) throws IOException {
        if (encoded.length() > (Attachment.THUMBNAIL_LIMIT + 2) / 3 * 4) throw invalidImage();
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length > Attachment.THUMBNAIL_LIMIT) throw invalidImage();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalidImage();
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase("jpeg")) throw invalidImage();
                reader.setInput(input, true, true);
                if (reader.getWidth(0) > 160 || reader.getHeight(0) > 160) throw invalidImage();
                return scale(reader.read(0), 98);
            } finally { reader.dispose(); }
        }
    }

    private static BufferedImage scale(BufferedImage image, int edge) {
        double ratio = Math.min(1, (double) edge / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(image.getHeight() * ratio));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return scaled;
    }

    private static ProtocolException invalidImage() {
        return new ProtocolException("INVALID_IMAGE", "图片无法读取或超过 2400 万像素");
    }
}
