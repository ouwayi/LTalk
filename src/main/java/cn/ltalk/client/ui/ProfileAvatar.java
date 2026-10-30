package cn.ltalk.client.ui;

import cn.ltalk.common.*;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

public final class ProfileAvatar implements Icon {
    private final BufferedImage image;
    private final Avatar fallback;
    private final int size;
    public ProfileAvatar(UserProfile profile, int size) {
        this.size = size; fallback = new Avatar(profile.nickname() == null ? profile.userId() : profile.nickname(), false, size);
        BufferedImage decoded = null;
        try { decoded = AvatarData.decode(profile.avatar()); } catch (java.io.IOException ignored) { }
        image = decoded;
    }
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        if (image == null) { fallback.paintIcon(c, graphics, x, y); return; }
        Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.clip(new Ellipse2D.Double(x, y, size, size)); g.drawImage(image, x, y, size, size, null); g.dispose();
    }
}
