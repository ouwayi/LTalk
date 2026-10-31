package cn.ltalk.client.ui;

import com.sun.jna.*;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Common picker entry; native work runs off the EDT, all completion callbacks run on it. */
public final class FileDialogs {
    public enum Kind { IMAGE, DIRECTORY }
    @FunctionalInterface interface Picker { Path choose(Component owner, Kind kind) throws Exception; }
    private final Picker picker;
    public FileDialogs() { this(FileDialogs::platformChoose); }
    FileDialogs(Picker picker) { this.picker = picker; }

    public void choose(Component owner, Kind kind, Consumer<Path> completed, Consumer<String> failed) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("文件选择须由 EDT 发起");
        new SwingWorker<Path, Void>() {
            protected Path doInBackground() throws Exception { return picker.choose(owner, kind); }
            protected void done() {
                try { completed.accept(get()); }
                catch (Exception ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    failed.accept("系统文件对话框未能打开：" + cause.getMessage());
                }
            }
        }.execute();
    }
    private static Path platformChoose(Component component, Kind kind) throws Exception {
        if (Platform.isWindows()) {
            Pointer[] handle = {null};
            SwingUtilities.invokeAndWait(() -> {
                Window window = component instanceof Window w ? w : SwingUtilities.getWindowAncestor(component);
                if (window != null && window.isDisplayable()) handle[0] = Native.getWindowPointer(window);
            });
            try { return WindowsFilePicker.choose(handle[0], kind); }
            catch (LinkageError ex) { throw new IOException("无法加载 Windows 文件选择组件", ex); }
        }
        Path[] selected = {null};
        SwingUtilities.invokeAndWait(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(kind == Kind.IMAGE ? "选择头像图片" : "选择聊天记录导出目录");
            if (kind == Kind.DIRECTORY) chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            else chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "gif", "bmp"));
            if (chooser.showOpenDialog(component) == JFileChooser.APPROVE_OPTION) selected[0] = chooser.getSelectedFile().toPath();
        });
        return selected[0];
    }
}
