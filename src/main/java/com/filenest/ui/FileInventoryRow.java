package com.filenest.ui;

import com.filenest.core.classifier.CategoryRules;
import com.filenest.core.storage.FolderSizeService;
import com.filenest.model.FileMeta;

import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Read-only presentation row for every top-level file found by the scanner. */
public final class FileInventoryRow {
    private static final DateTimeFormatter MODIFIED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final FileMeta file;

    public FileInventoryRow(FileMeta file) {
        this.file = file;
    }

    public String getFileName() { return file.fileName(); }
    public String getType() { return file.extension().isBlank() ? "（无扩展名）" : "." + file.extension(); }
    public String getCategory() { return CategoryRules.categoryFor(file.extension()); }
    public String getSize() { return FolderSizeService.formatBytes(file.size()); }
    public String getModified() { return MODIFIED.format(file.lastModified()); }

    public String getSafety() {
        String name = file.fileName().toLowerCase(Locale.ROOT);
        if (Files.isSymbolicLink(file.path())) return "符号链接（仅显示）";
        if (file.hidden() || name.startsWith(".") || name.equals("thumbs.db")
                || name.equals("desktop.ini") || name.equals("ntuser.dat")) {
            return "隐藏/系统配置（仅显示）";
        }
        if (!CategoryRules.isKnown(file.extension())) return "未知类型（默认不自动整理）";
        return "可整理";
    }
}
