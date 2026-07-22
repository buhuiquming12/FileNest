package com.filenest.core.storage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Immutable disk-usage inventory. It contains facts only, not cleanup judgments. */
public record StorageScanResult(Path root, FolderSizeService.DirectoryStats total,
                                List<FolderUsage> folders, List<LargeFileUsage> largestFiles,
                                Instant scannedAt) {
    public StorageScanResult {
        root = root.toAbsolutePath().normalize();
        folders = List.copyOf(folders);
        largestFiles = List.copyOf(largestFiles);
        scannedAt = scannedAt == null ? Instant.now() : scannedAt;
    }
}
