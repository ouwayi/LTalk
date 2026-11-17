package cn.ltalk.client.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FileDialogsTest {
    @Test void imagePickerRejectsNonImagesAndAttachmentPickerAcceptsBoth() throws Exception {
        for (Path candidate : new Path[]{Path.of("report.pdf"), Path.of("photo.PNG")}) {
            for (var kind : new FileDialogs.Kind[]{FileDialogs.Kind.CHAT_IMAGE, FileDialogs.Kind.FILE}) {
                var selection = new CompletableFuture<Object>();
                FileDialogs dialogs = new FileDialogs((owner, actual) -> {
                    assertEquals(kind, actual);
                    return candidate;
                });
                SwingUtilities.invokeAndWait(() -> dialogs.choose(null, kind, selection::complete, selection::complete));
                Object result = selection.get(4, TimeUnit.SECONDS);
                if (kind == FileDialogs.Kind.CHAT_IMAGE && candidate.toString().endsWith("pdf"))
                    assertTrue(result instanceof String text && text.contains("请选择"));
                else assertEquals(candidate, result);
            }
        }
    }
    @Test void selectedTypeDeterminesExtensionWithoutLosingCustomNames() {
        assertEquals(Path.of("中文 名称.csv"), FileDialogs.exportPath(Path.of("中文 名称.json"), "csv"));
        assertEquals(Path.of("中文 名称.json"), FileDialogs.exportPath(Path.of("中文 名称"), "json"));
        assertEquals(Path.of("报告.JSON"), FileDialogs.exportPath(Path.of("报告.JSON"), "json"));
        assertThrows(IllegalArgumentException.class, () -> FileDialogs.exportPath(Path.of("x"), "exe"));
    }
    @Test void selectionCancellationAndFailureReturnOnEdtWithoutDoingWorkThere() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) {
            int value = scenario; CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Object> result = new AtomicReference<>();
            FileDialogs dialogs = new FileDialogs((owner, kind) -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                if (value == 2) throw new IOException("native unavailable");
                return value == 0 ? Path.of("中文 头像.png") : null;
            });
            SwingUtilities.invokeAndWait(() -> dialogs.choose(null, FileDialogs.Kind.IMAGE, selected -> {
                assertTrue(SwingUtilities.isEventDispatchThread()); result.set(selected); done.countDown();
            }, detail -> { assertTrue(SwingUtilities.isEventDispatchThread()); result.set(detail); done.countDown(); }));
            assertTrue(done.await(4, TimeUnit.SECONDS));
            if (value == 0) assertEquals(Path.of("中文 头像.png"), result.get());
            else if (value == 1) assertNull(result.get(), "Cancel must not produce a path or an error");
            else assertTrue(result.get().toString().contains("native unavailable"));
        }
    }
}
