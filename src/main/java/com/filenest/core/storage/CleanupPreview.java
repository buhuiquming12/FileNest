package com.filenest.core.storage;

import java.nio.file.Path;
import java.util.List;

/** Result of a cleanup preview, including current system-drive capacity. */
public record CleanupPreview(
        Path drive,
        long totalSpace,
        long freeSpace,
        List<CleanupCandidate> candidates,
        int inaccessibleCount
) {
    public long reclaimableBytes() {
        return candidates.stream().mapToLong(CleanupCandidate::size).sum();
    }
}
