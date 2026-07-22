package com.filenest.ui;

import com.filenest.core.storage.FolderSizeService;
import com.filenest.core.storage.FolderUsage;
import com.filenest.core.storage.StorageScanResult;

/** Formatted row for the folder-usage table. */
public final class FolderUsageRow {
    private final FolderUsage usage;
    private final StorageScanResult scan;

    public FolderUsageRow(FolderUsage usage, StorageScanResult scan) {
        this.usage = usage;
        this.scan = scan;
    }

    public String getFolder() {
        return usage.rootFiles() ? "（根目录文件）" : usage.path().getFileName().toString();
    }
    public String getSize() { return FolderSizeService.formatBytes(usage.bytes()); }
    public double getPercent() {
        return scan.total().bytes() == 0 ? 0 : usage.bytes() * 100.0 / scan.total().bytes();
    }
    public long getFileCount() { return usage.fileCount(); }
    public long getFolderCount() { return usage.folderCount(); }
    public String getPath() { return usage.path().toString(); }
}
