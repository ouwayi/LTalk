package cn.ltalk.client.ui;

import cn.ltalk.client.ContactProfile;
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
        this(ContactProfile.displayName(profile.userId(), profile.nickname()), decode(profile.avatar()), size);
    }
    private ProfileAvatar(String name, BufferedImage image, int size) {
        this.size = size; this.image = image;
        fallback = new Avatar(name, false, size);
    }
    private static BufferedImage decode(String avatar) {
        try { return AvatarData.decode(avatar); } catch (java.io.IOException ignored) { return null; }
    }
    public static Icon contact(ContactProfile profile, String fallback, int size) {
        return profile == null ? new Avatar(fallback, false, size) : new ProfileAvatar(profile.displayName(), profile.avatar(), size);
    }
    public int getIconWidth() { return size; }
    public int getIconHeight() { return size; }
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
        if (image == null) { fallback.paintIcon(c, graphics, x, y); return; }
        Graphics2D g = (Graphics2D) graphics.create(); ChatTheme.quality(g);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.clip(new Ellipse2D.Double(x, y, size, size)); g.drawImage(image, x, y, size, size, null); g.dispose();
    }
}
