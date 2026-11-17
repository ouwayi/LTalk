package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationListTest {
    @Test void selectedConversationRendersColorInTitleAndNarrowPreview() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new DefaultListModel<ConversationStore.Summary>();
            model.addElement(new ConversationStore.Summary(ConversationId.PUBLIC, "😀会话",
                    "你：😀😀\n这段内容需要省略", Instant.EPOCH, 7, true, null));
            var list = new ConversationList(model);
            list.setSelectedIndex(0);
            list.setSize(190, 100);
            var image = new BufferedImage(190, 100, BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try { list.paint(graphics); }
            finally { graphics.dispose(); }
            assertTrue(EmojiTextTest.goldPixels(image, 82, 19, 127, 37) > 35, "Emoji in the title");
            assertTrue(EmojiTextTest.goldPixels(image, 82, 43, 150, 62) > 35, "Emoji in the truncated preview");
            assertEquals(0, EmojiTextTest.goldPixels(image, 150, 43, 182, 62), "Preview must stay clear of the unread badge");
        });
    }
}
