package com.filenest.core.storage;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Immutable disk-usage inventory. It contains facts only, not cleanup judgments. */
public record StorageScanResult(Path root, FolderSizeService.DirectoryStats total,
                                List<FolderUsage> folders, List<FolderUsage> folderSamples,
                                List<LargeFileUsage> largestFiles, Instant scannedAt) {
    public StorageScanResult {
        root = root.toAbsolutePath().normalize();
        folders = List.copyOf(folders);
        folderSamples = List.copyOf(folderSamples);
        largestFiles = List.copyOf(largestFiles);
        scannedAt = scannedAt == null ? Instant.now() : scannedAt;
    }

    /** Backwards-compatible constructor for callers that do not provide nested folder samples. */
    public StorageScanResult(Path root, FolderSizeService.DirectoryStats total,
                             List<FolderUsage> folders, List<LargeFileUsage> largestFiles,
                             Instant scannedAt) {
        this(root, total, folders, folders.stream().filter(folder -> !folder.rootFiles()).toList(),
                largestFiles, scannedAt);
    }
}
