package com.filenest.core.storage;

import java.nio.file.Path;
import java.time.Instant;

/** Metadata for a large file surfaced by a storage scan. */
public record LargeFileUsage(Path path, long bytes, Instant lastModified) { }
