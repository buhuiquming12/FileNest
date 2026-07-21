package com.filenest.model;

import java.util.List;

/**
 * A set of files found to be byte-for-byte identical (same content hash).
 *
 * <p>Produced by the deterministic duplicate detector — <i>not</i> the AI. The first
 * element is treated as the copy to keep; the rest are the redundant duplicates.
 */
public record DuplicateGroup(String hash, List<FileMeta> files) {

    public DuplicateGroup {
        files = List.copyOf(files);
        if (files.size() < 2) {
            throw new IllegalArgumentException("a duplicate group needs at least 2 files");
        }
    }

    /** The copy we keep (the first one scanned). */
    public FileMeta keeper() {
        return files.get(0);
    }

    /** The redundant copies that may be archived/removed. */
    public List<FileMeta> duplicates() {
        return files.subList(1, files.size());
    }
}
