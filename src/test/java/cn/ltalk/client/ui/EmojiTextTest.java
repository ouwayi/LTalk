package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.StyleConstants;
import java.awt.Color;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class EmojiTextTest {
    @Test void firstPaintUsesColorAtTheEndOfTheDocumentAndAfterEditing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var composer = new ComposerInput();
            var palette = ThemePalette.of(AppearanceSettings.defaults());
            for (String value : new String[]{"😀", "正文😀", "正文☺️", "上一行\n😀"}) {
                composer.setText(value);
                assertTrue(goldPixels(paint(composer), 0, 0, 240, 70) > 70, "Draft: " + value);
                assertTrue(goldPixels(paint(new MessageText(value, palette)), 0, 0, 240, 70) > 70, "Message: " + value);
                assertEquals(value, composer.getText());
            }
            composer.setText("正文");
            composer.setCaretPosition(composer.getDocument().getLength());
            composer.replaceSelection("😀");
            assertTrue(goldPixels(paint(composer), 0, 0, 240, 70) > 70, "Just appended an emoji");
            composer.replaceSelection("文");
            key(composer, DefaultEditorKit.deletePrevCharAction);
            assertEquals("正文😀", composer.getText());
            assertTrue(goldPixels(paint(composer), 0, 0, 240, 70) > 70, "Deleted the trailing text");
        });
    }

    @Test void composerAndBubblePaintColorIconsWhileClipboardKeepsUnicode() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String text = "中😀😀❤️文\n第二行";
            var composer = new ComposerInput();
            composer.setText(text);
            var palette = ThemePalette.of(AppearanceSettings.defaults().withMode(AppearanceSettings.Mode.DARK));
            var bubble = new MessageText(text, palette);
            for (EmojiText view : new EmojiText[]{composer, bubble}) {
                assertEquals(text, view.getText());
                assertColorIcons(view);
                assertEquals(3, view.getStyledDocument().getCharacterElement(1).getEndOffset());
                assertEquals(5, view.getStyledDocument().getCharacterElement(3).getEndOffset());
                view.selectAll();
                var clipboard = new Clipboard("emoji-test");
                view.getTransferHandler().exportToClipboard(view, clipboard, TransferHandler.COPY);
                assertEquals(text, clipboardText(clipboard));
            }
        });
    }

    @Test void caretAndBothDeleteKeysTreatRenderedEmojiAsWholeCharacters() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var input = new ComposerInput();
            input.setText("甲😀😀❤️乙");
            assertColorIcons(input);
            input.setCaretPosition(1);
            key(input, DefaultEditorKit.forwardAction);
            assertEquals(3, input.getCaretPosition());
            key(input, DefaultEditorKit.backwardAction);
            assertEquals(1, input.getCaretPosition());
            input.setCaretPosition(2);
            assertEquals(3, input.getCaretPosition());
            key(input, DefaultEditorKit.deletePrevCharAction);
            assertEquals("甲😀❤️乙", input.getText());
            assertEquals(1, input.getCaretPosition());
            key(input, DefaultEditorKit.deleteNextCharAction);
            assertEquals("甲❤️乙", input.getText());
            input.setCaretPosition(3);
            key(input, DefaultEditorKit.deletePrevCharAction);
            assertEquals("甲乙", input.getText());
            input.replaceSelection("🥰");
            assertEquals("甲🥰乙", input.getText());
            assertNotNull(StyleConstants.getIcon(input.getStyledDocument().getCharacterElement(1).getAttributes()));
        });
    }

    private static void assertColorIcons(EmojiText view) {
        assertTrue(goldPixels(paint(view), 0, 0, 100, 30) > 180, "Two adjacent smiles should both paint in color");
    }

    private static BufferedImage paint(EmojiText view) {
        view.setSize(240, 70);
        var image = new BufferedImage(240, 70, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try { view.paint(graphics); }
        finally { graphics.dispose(); }
        return image;
    }

    static int goldPixels(BufferedImage image, int left, int top, int right, int bottom) {
        int gold = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            var pixel = new Color(image.getRGB(x, y), true);
            if (pixel.getAlpha() > 200 && pixel.getRed() > 220 && pixel.getGreen() > 150 && pixel.getBlue() < 115)
                gold++;
        }
        return gold;
    }

    private static Object clipboardText(Clipboard clipboard) {
        try { return clipboard.getData(DataFlavor.stringFlavor); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }

    private static void key(EmojiText input, String key) {
        input.getActionMap().get(key).actionPerformed(new ActionEvent(input, ActionEvent.ACTION_PERFORMED, key));
    }
}
