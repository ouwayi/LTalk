package cn.ltalk.client;

import cn.ltalk.common.AvatarData;
import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Base64;

/** Local image read, resize and compression run off the EDT. */
public final class AvatarImporter {
    private AvatarImporter() { }
    public static String load(Path path) throws IOException {
        if (Files.size(path) > 8 * 1024 * 1024) throw new IOException("图片不能超过 8 MiB");
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw new IOException("无法读取图片");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("请选择 PNG、JPEG 等有效图片");
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 16_000_000) throw new IOException("图片像素不能超过 1600 万");
                BufferedImage source = reader.read(0);
                BufferedImage scaled = new BufferedImage(AvatarData.SIZE, AvatarData.SIZE, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = scaled.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    int side = Math.min(width, height), left = (width - side) / 2, top = (height - side) / 2;
                    g.drawImage(source, 0, 0, AvatarData.SIZE, AvatarData.SIZE, left, top, left + side, top + side, null);
                } finally { g.dispose(); }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(scaled, "png", output);
                byte[] bytes = output.size() <= AvatarData.MAX_BYTES ? output.toByteArray() : encodePhoto(scaled);
                return Base64.getEncoder().encodeToString(bytes);
            } finally { reader.dispose(); }
        }
    }

    private static byte[] encodePhoto(BufferedImage scaled) throws IOException {
        BufferedImage photo = new BufferedImage(scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        try { g.drawImage(scaled, 0, 0, Color.WHITE, null); } finally { g.dispose(); }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            var params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            for (float quality : new float[]{.95f, .88f, .80f, .70f, .55f, .40f, .25f, .10f}) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (var output = new MemoryCacheImageOutputStream(bytes)) {
                    writer.setOutput(output);
                    params.setCompressionQuality(quality);
                    writer.write(null, new IIOImage(photo, null, null), params);
                }
                if (bytes.size() <= AvatarData.MAX_BYTES) return bytes.toByteArray();
            }
        } finally { writer.dispose(); }
        throw new IOException("图片压缩后仍超过头像大小限制");
    }
}
