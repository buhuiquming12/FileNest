package com.filenest.core.storage;

import java.nio.file.Path;

/** Recursive usage of one direct child folder (or files directly under the scanned root). */
public record FolderUsage(Path path, long bytes, long fileCount, long folderCount,
                          long inaccessibleCount, boolean rootFiles) { }
