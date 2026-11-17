package cn.ltalk.client.ui;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class EmojiIcons {
    record Choice(String value, String label) { }
    static final Pattern GRAPHEME = Pattern.compile("\\X");
    static final int INLINE_SIZE = 18;
    static final List<Choice> CHOICES = List.of(
            new Choice("😀", "大笑"), new Choice("😃", "开心"), new Choice("😄", "笑眯眼"),
            new Choice("😁", "咧嘴笑"), new Choice("😆", "眯眼大笑"), new Choice("😅", "笑出汗"),
            new Choice("😂", "笑出眼泪"), new Choice("🤣", "笑得打滚"), new Choice("😇", "天使笑脸"),
            new Choice("😉", "眨眼"), new Choice("😊", "害羞微笑"), new Choice("🙂", "微笑"),
            new Choice("🙃", "倒着笑"), new Choice("☺️", "红脸微笑"), new Choice("😋", "好吃"),
            new Choice("😌", "松一口气"), new Choice("😍", "爱心眼"), new Choice("🥰", "被爱包围"),
            new Choice("😘", "飞吻"), new Choice("😗", "亲亲"), new Choice("😚", "闭眼亲亲"),
            new Choice("😙", "笑着亲亲"), new Choice("🥲", "含泪微笑"), new Choice("😜", "眨眼吐舌"),
            new Choice("🤪", "搞怪"), new Choice("😝", "闭眼吐舌"), new Choice("😛", "吐舌"),
            new Choice("🤑", "财迷"), new Choice("😎", "墨镜酷脸"), new Choice("🤓", "眼镜笑脸"),
            new Choice("🧐", "单片眼镜"), new Choice("🤠", "牛仔"), new Choice("🥳", "庆祝脸"),
            new Choice("😏", "坏笑"), new Choice("😶", "无语"), new Choice("😐", "面无表情"),
            new Choice("😑", "眯眼无语"), new Choice("😒", "不高兴"), new Choice("🙄", "翻白眼"),
            new Choice("🤨", "挑眉"), new Choice("🤔", "思考"), new Choice("🤫", "嘘"),
            new Choice("🤭", "捂嘴笑"), new Choice("🫢", "惊讶捂嘴"), new Choice("🫡", "敬礼"),
            new Choice("🤗", "抱抱"), new Choice("😮", "吃惊"), new Choice("😳", "脸红"),
            new Choice("😔", "难过"), new Choice("🥺", "委屈"), new Choice("😢", "流泪"),
            new Choice("😭", "大哭"), new Choice("😤", "气鼓鼓"), new Choice("😠", "生气"),
            new Choice("😡", "愤怒"), new Choice("😴", "睡觉"), new Choice("😱", "吓一跳"),
            new Choice("👍", "赞"), new Choice("👎", "踩"), new Choice("👏", "鼓掌"),
            new Choice("🙌", "举手欢呼"), new Choice("🙏", "谢谢"), new Choice("👋", "挥手"),
            new Choice("❤️", "红心"), new Choice("🔥", "火焰"), new Choice("🎉", "礼花"),
            new Choice("⭐", "星星"), new Choice("✅", "勾选"), new Choice("💡", "灯泡")
    );
    private static final Map<String, BufferedImage> IMAGES = new HashMap<>();
    private static final Map<String, String> FILES = files();

    private EmojiIcons() { }

    private static Map<String, String> files() {
        Map<String, String> files = new HashMap<>();
        for (Choice choice : CHOICES) {
            String bare = choice.value().replace("\uFE0F", "");
            String file = bare.codePoints().mapToObj(Integer::toHexString).collect(Collectors.joining("-"));
            files.put(choice.value(), file);
            // The original Unicode heart and smile can also occur without an emoji selector.
            files.put(bare, file);
        }
        return Map.copyOf(files);
    }

    static boolean supports(String value) { return FILES.containsKey(value); }

    static Icon icon(String value, int size) {
        String name = FILES.get(value);
        if (name == null) return null;
        BufferedImage image = IMAGES.computeIfAbsent(name, EmojiIcons::load);
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(image, x, y, size, size, null);
                } finally { g.dispose(); }
            }
        };
    }

    private static BufferedImage load(String name) {
        try (var input = EmojiIcons.class.getResourceAsStream("emoji/" + name + ".png")) {
            if (input == null) throw new IOException("找不到内置 Emoji");
            BufferedImage image = ImageIO.read(input);
            if (image == null) throw new IOException("无效的内置 Emoji");
            return image;
        } catch (IOException ex) { throw new IllegalStateException(name, ex); }
    }
}
