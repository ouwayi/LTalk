package cn.ltalk.client.ui;

import com.sun.jna.*;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Common picker entry; native work runs off the EDT, all completion callbacks run on it. */
public final class FileDialogs {
    public record SaveChoice(Path path, String format, boolean replace) { }
    public enum Kind { IMAGE, DIRECTORY }
    @FunctionalInterface interface Picker { Path choose(Component owner, Kind kind) throws Exception; }
    private final Picker picker;
    public FileDialogs() { this(FileDialogs::platformChoose); }
    FileDialogs(Picker picker) { this.picker = picker; }
    public void saveExport(Component owner, String suggestedName, Consumer<SaveChoice> completed, Consumer<String> failed) {
        new SwingWorker<SaveChoice, Void>() {
            protected SaveChoice doInBackground() throws Exception {
                if (Platform.isWindows()) return WindowsFilePicker.save(windowHandle(owner), suggestedName);
                SaveChoice[] value = {null};
                SwingUtilities.invokeAndWait(() -> {
                    JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("导出聊天记录");
                    var json = new javax.swing.filechooser.FileNameExtensionFilter("JSON 聊天记录", "json");
                    var csv = new javax.swing.filechooser.FileNameExtensionFilter("CSV 聊天记录", "csv");
                    chooser.setAcceptAllFileFilterUsed(false); chooser.addChoosableFileFilter(json); chooser.addChoosableFileFilter(csv); chooser.setFileFilter(json);
                    chooser.setSelectedFile(new java.io.File(suggestedName));
                    if (chooser.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION)
                        value[0] = new SaveChoice(chooser.getSelectedFile().toPath(), chooser.getFileFilter() == csv ? "csv" : "json", false);
                }); return value[0];
            }
            protected void done() {
                try {
                    SaveChoice choice = get();
                    if (choice == null || choice.path() == null) { completed.accept(null); return; }
                    Path path = exportPath(choice.path(), choice.format()); boolean exists = java.nio.file.Files.exists(path);
                    if (exists && JOptionPane.showConfirmDialog(owner, "文件已存在，是否替换？\n" + path, "确认替换", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) { completed.accept(null); return; }
                    completed.accept(new SaveChoice(path, choice.format(), exists));
                } catch (Exception ex) { failed.accept("保存对话框失败：" + (ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage())); }
            }
        }.execute();
    }
    public static Path exportPath(Path selected, String format) {
        if (!java.util.Set.of("json", "csv").contains(format)) throw new IllegalArgumentException("无效保存类型");
        String name = selected.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith("." + format)) return selected;
        name = name.replaceFirst("(?i)\\.(json|csv)$", ""); return selected.resolveSibling(name + "." + format);
    }
    private static Pointer windowHandle(Component component) throws Exception {
        Pointer[] handle = {null};
        SwingUtilities.invokeAndWait(() -> {
            Window window = component instanceof Window w ? w : component == null ? null : SwingUtilities.getWindowAncestor(component);
            if (window != null && window.isDisplayable()) handle[0] = Native.getWindowPointer(window);
        }); return handle[0];
    }

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
            try { return WindowsFilePicker.choose(windowHandle(component), kind); }
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
