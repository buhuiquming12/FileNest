package com.filenest.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Immutable inventory produced by scanning, before any rule or AI suggestion is made. */
public record OrganizeScan(Path rootDir, List<FileMeta> files, Instant scannedAt) {
    public OrganizeScan {
        if (rootDir == null) {
            throw new IllegalArgumentException("rootDir 不能为空");
        }
        rootDir = rootDir.toAbsolutePath().normalize();
        files = List.copyOf(files);
        scannedAt = scannedAt == null ? Instant.now() : scannedAt;
    }
}
