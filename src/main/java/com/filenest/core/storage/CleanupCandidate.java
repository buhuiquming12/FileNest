package com.filenest.core.storage;

import java.nio.file.Path;
import java.time.Instant;

/** One previewable item in a safe, allow-listed cleanup location. */
public record CleanupCandidate(
        Path path,
        String category,
        long size,
        Instant lastModified,
        boolean directory,
        boolean recommended
) { }
