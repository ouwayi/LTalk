package cn.ltalk.client.ui;

import cn.ltalk.common.Attachment;
import com.sun.jna.*;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Common picker entry; native work runs off the EDT, all completion callbacks run on it. */
public final class FileDialogs {
    public record SaveChoice(Path path, String format, boolean replace) { }
    public enum Kind {
        IMAGE("选择头像图片"), DIRECTORY("选择聊天记录导出目录"), CHAT_IMAGE("选择要发送的图片"), FILE("选择要发送的附件");
        final String title;
        Kind(String title) { this.title = title; }
        boolean images() { return this == IMAGE || this == CHAT_IMAGE; }
    }
    @FunctionalInterface interface Picker { Path choose(Component owner, Kind kind) throws Exception; }
    private final Picker picker;
    public FileDialogs() { this(FileDialogs::platformChoose); }
    FileDialogs(Picker picker) { this.picker = picker; }
    public void saveExport(Component owner, String suggestedName, Consumer<SaveChoice> completed, Consumer<String> failed) {
        save(owner, suggestedName, true, completed, failed);
    }
    public void saveAttachment(Component owner, String suggestedName, Consumer<SaveChoice> completed, Consumer<String> failed) {
        save(owner, suggestedName, false, completed, failed);
    }
    private void save(Component owner, String suggestedName, boolean export, Consumer<SaveChoice> completed, Consumer<String> failed) {
        new SwingWorker<SaveChoice, Void>() {
            protected SaveChoice doInBackground() throws Exception {
                SaveChoice choice;
                if (Platform.isWindows()) choice = WindowsFilePicker.save(windowHandle(owner), suggestedName, export);
                else {
                    SaveChoice[] value = {null};
                    SwingUtilities.invokeAndWait(() -> {
                        JFileChooser chooser = new JFileChooser();
                        chooser.setDialogTitle(export ? "导出聊天记录" : "保存附件");
                        var json = new javax.swing.filechooser.FileNameExtensionFilter("JSON 聊天记录", "json");
                        var csv = new javax.swing.filechooser.FileNameExtensionFilter("CSV 聊天记录", "csv");
                        if (export) {
                            chooser.setAcceptAllFileFilterUsed(false);
                            chooser.addChoosableFileFilter(json);
                            chooser.addChoosableFileFilter(csv);
                            chooser.setFileFilter(json);
                        }
                        chooser.setSelectedFile(new java.io.File(suggestedName));
                        if (chooser.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION) {
                            String format = export ? chooser.getFileFilter() == csv ? "csv" : "json" : null;
                            value[0] = new SaveChoice(chooser.getSelectedFile().toPath(), format, false);
                        }
                    });
                    choice = value[0];
                }
                if (choice == null || choice.path() == null) return null;
                Path path = export ? exportPath(choice.path(), choice.format()) : choice.path();
                if (Files.isDirectory(path)) throw new IOException("请选择一个文件名");
                return new SaveChoice(path, choice.format(), Files.exists(path));
            }
            protected void done() {
                try {
                    SaveChoice choice = get();
                    if (choice != null && choice.replace() && JOptionPane.showConfirmDialog(owner,
                            "文件已存在，是否替换？\n" + choice.path(), "确认替换", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                        choice = null;
                    completed.accept(choice);
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
                try {
                    Path chosen = get();
                    if (chosen != null && kind == Kind.CHAT_IMAGE && !Attachment.isImageName(chosen.getFileName().toString()))
                        failed.accept("请选择 PNG、JPEG、GIF 或 BMP 图片");
                    else completed.accept(chosen);
                }
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
            chooser.setDialogTitle(kind.title);
            if (kind == Kind.DIRECTORY) chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            else if (kind.images()) {
                if (kind == Kind.CHAT_IMAGE) chooser.setAcceptAllFileFilterUsed(false);
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("图片", "png", "jpg", "jpeg", "gif", "bmp"));
            }
            if (chooser.showOpenDialog(component) == JFileChooser.APPROVE_OPTION) selected[0] = chooser.getSelectedFile().toPath();
        });
        return selected[0];
    }
}
