package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ThemeRenderingTest {
    @Test void disabledButtonsUseLegibleSingleToneTextOnNeutralDarkSurfaces() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var dark = AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK);
            ThemePalette p = ThemePalette.of(dark);
            for (Color color : new Color[]{p.surface(), p.soft(), p.line(), p.hover(), p.disabled(), p.outgoing(), dark.start(), dark.end()}) {
                assertEquals(color.getRed(), color.getGreen()); assertEquals(color.getRed(), color.getBlue());
            }
            JPanel root = new JPanel(); ChatTheme.apply(root, p);
            AbstractButton[] buttons = {ChatTheme.primary("连接服务器"), ChatTheme.primary("登录"), ChatTheme.primary("发送"),
                    ChatTheme.primary("保存资料"), ChatTheme.pill("立即注册"), ChatTheme.choice("显示"), ChatTheme.choice("隐藏")};
            for (AbstractButton button : buttons) {
                root.add(button); button.setEnabled(false); button.setSize(180, 44); ChatTheme.apply(root, p);
                BufferedImage image = new BufferedImage(180, 44, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics(); g.setColor(p.surface()); g.fillRect(0, 0, 180, 44); button.paint(g); g.dispose();
                int solidText = 0;
                for (int y = 0; y < 44; y++) for (int x = 0; x < 180; x++) {
                    Color pixel = new Color(image.getRGB(x, y));
                    assertTrue(pixel.getRed() <= p.muted().getRed() && pixel.getGreen() <= p.muted().getGreen()
                            && pixel.getBlue() <= p.muted().getBlue(), "Disabled text must not contain the default bright embossed copy: " + button.getText());
                    if (pixel.equals(p.muted())) solidText++;
                }
                assertTrue(solidText > 10, "Disabled label must remain visible: " + button.getText());
            }
            assertTrue(contrast(p.muted(), p.disabled()) > 4.5);
            ConnectionPanel connection = new ConnectionPanel(null, null); connection.state(false, false);
            AuthenticationPanel authentication = new AuthenticationPanel("preview"); authentication.state(false, false);
            WelcomePanel welcome = new WelcomePanel(connection, authentication); ChatTheme.apply(welcome, p);
            render(welcome, 1000, 720, "welcome-neutral-dark.png");
            render(welcome, 420, 1100, "welcome-neutral-dark-narrow.png");
            authentication.setRegistering(true); authentication.state(true, true);
            render(welcome, 1000, 820, "registration-neutral-dark-busy.png");
        });
    }
    private static double contrast(Color first, Color second) { return (luminance(first) + .05) / (luminance(second) + .05); }
    private static double luminance(Color color) {
        return .2126 * channel(color.getRed()) + .7152 * channel(color.getGreen()) + .0722 * channel(color.getBlue());
    }
    private static double channel(int channel) { double v = channel / 255d; return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
    private static void render(JPanel panel, int width, int height, String name) {
        try {
            panel.setSize(width, height); layout(panel);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics(); panel.paint(g); g.dispose();
            Path path = Path.of("target/ui-preview", name); Files.createDirectories(path.getParent()); ImageIO.write(image, "png", path.toFile());
        } catch (Exception ex) { throw new AssertionError(ex); }
    }
    private static void layout(Container container) {
        container.doLayout(); for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
