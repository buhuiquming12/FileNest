package com.filenest.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

/**
 * Immutable metadata snapshot of a single scanned file.
 *
 * <p>This is what the {@code Scanner} produces and what {@code Classifier} /
 * {@code AiAdvisor} consume. It deliberately holds only cheap-to-read metadata;
 * content peeking (for AI heuristics) is done on demand from {@link #path()}.
 */
public record FileMeta(
        Path path,
        String fileName,
        String extension,   // lower-case, without the dot; "" when the file has none
        long size,          // bytes
        Instant lastModified,
        boolean directory,
        boolean hidden
) {
    /**
     * Builds a {@link FileMeta} from a path by reading its basic attributes.
     */
    public static FileMeta of(Path path) throws IOException {
        BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
        String name = path.getFileName().toString();
        return new FileMeta(
                path,
                name,
                extensionOf(name),
                attr.size(),
                attr.lastModifiedTime().toInstant(),
                attr.isDirectory(),
                isHidden(path)
        );
    }

    private static boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException | SecurityException ignored) {
            // Unknown is treated as visible for display; the executor still validates before moving.
            return false;
        }
    }
    /** File name without the extension (e.g. {@code report.final.docx} -> {@code report.final}). */
    public String baseName() {
        int dot = fileName.lastIndexOf('.');
        return (dot <= 0) ? fileName : fileName.substring(0, dot);
    }

    /** Extracts a normalized (lower-case, no dot) extension, or "" if there is none. */
    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }
}
