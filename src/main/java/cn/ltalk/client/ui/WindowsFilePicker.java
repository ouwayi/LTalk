package cn.ltalk.client.ui;

import com.sun.jna.*;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.*;
import java.io.IOException;
import java.nio.file.Path;

/** Windows Common Item Dialog, not the legacy Swing or OPENFILENAME picker. */
final class WindowsFilePicker {
    private static final int CANCELLED = 0x800704c7;
    private WindowsFilePicker() { }

    // All COM pointers stay on this STA thread and are released before CoUninitialize.
    static Path choose(Pointer owner, FileDialogs.Kind kind) throws IOException {
        return show(owner, kind, null).path();
    }
    static FileDialogs.SaveChoice save(Pointer owner, String name) throws IOException {
        return show(owner, null, name);
    }
    private static FileDialogs.SaveChoice show(Pointer owner, FileDialogs.Kind kind, String name) throws IOException {
        boolean save = name != null;
        Ole32 ole = Ole32.INSTANCE;
        check(ole.CoInitializeEx(null, 2).intValue(), "初始化文件对话框");
        Pointer dialog = null, item = null, path = null;
        FilterSpec filter = null;
        try {
            PointerByReference result = new PointerByReference();
            check(ole.CoCreateInstance(new Guid.CLSID(save ? "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}" : "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"), null, 1,
                    new Guid.IID(save ? "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}" : "{D57C7288-D4AD-4768-BE02-9D969532D960}"), result).intValue(), "创建文件对话框");
            dialog = result.getValue();
            IntByReference options = new IntByReference(); check(call(dialog, 10, options), "读取文件选项");
            int flags = options.getValue() | 0x8 | 0x40 | 0x800; // NOCHANGEDIR, FORCEFILESYSTEM, PATHMUSTEXIST
            if (kind == FileDialogs.Kind.DIRECTORY) flags |= 0x20; // PICKFOLDERS
            else if (!save) flags |= 0x1000; // FILEMUSTEXIST
            if (save) flags = (flags | 0x4) & ~0x2; // Strict types; replacement is confirmed for the final normalized path on EDT.
            check(call(dialog, 9, flags), "设置文件选项");
            check(call(dialog, 17, new WString(save ? "导出聊天记录" : kind == FileDialogs.Kind.IMAGE ? "选择头像图片" : "选择聊天记录导出目录")), "设置标题");
            if (kind == FileDialogs.Kind.IMAGE || save) {
                filter = new FilterSpec(); FilterSpec[] filters = (FilterSpec[]) filter.toArray(save ? 2 : 1);
                filters[0].name = new WString(save ? "JSON 聊天记录 (*.json)" : "图片 (PNG / JPEG / GIF / BMP)");
                filters[0].pattern = new WString(save ? "*.json" : "*.png;*.jpg;*.jpeg;*.gif;*.bmp"); filters[0].write();
                if (save) { filters[1].name = new WString("CSV 聊天记录 (*.csv)"); filters[1].pattern = new WString("*.csv"); filters[1].write(); }
                check(call(dialog, 4, filters.length, filter.getPointer()), "设置文件类型");
                if (save) { check(call(dialog, 15, new WString(name)), "设置文件名"); check(call(dialog, 22, new WString("json")), "设置扩展名"); }
                // Keep both the structures and their native strings alive throughout Show.
                filter.retained = filters;
            }
            int shown = call(dialog, 3, owner);
            if (shown == CANCELLED) return new FileDialogs.SaveChoice(null, null, false);
            check(shown, "打开文件对话框");
            check(call(dialog, 20, result), "读取所选项目"); item = result.getValue();
            PointerByReference displayName = new PointerByReference();
            check(call(item, 5, 0x80058000, displayName), "读取所选路径"); // SIGDN_FILESYSPATH
            path = displayName.getValue();
            IntByReference type = new IntByReference(1); if (save) check(call(dialog, 6, type), "读取保存类型");
            return new FileDialogs.SaveChoice(Path.of(path.getWideString(0)), type.getValue() == 2 ? "csv" : "json", false);
        } finally {
            java.lang.ref.Reference.reachabilityFence(filter);
            if (path != null) ole.CoTaskMemFree(path);
            if (item != null) call(item, 2);
            if (dialog != null) call(dialog, 2);
            ole.CoUninitialize();
        }
    }
    private static int call(Pointer object, int method, Object... arguments) {
        Object[] nativeArguments = new Object[arguments.length + 1]; nativeArguments[0] = object;
        System.arraycopy(arguments, 0, nativeArguments, 1, arguments.length);
        Pointer function = object.getPointer(0).getPointer((long) method * Native.POINTER_SIZE);
        return Function.getFunction(function, Function.ALT_CONVENTION).invokeInt(nativeArguments);
    }
    private static void check(int result, String action) throws IOException {
        if (result < 0) throw new IOException(action + "失败（0x" + Integer.toHexString(result) + "）");
    }
    @Structure.FieldOrder({"name", "pattern"})
    public static final class FilterSpec extends Structure {
        public WString name, pattern;
        private Object retained;
    }
}
