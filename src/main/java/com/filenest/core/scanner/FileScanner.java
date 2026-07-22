package com.filenest.core.scanner;

import com.filenest.model.FileMeta;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans a directory and produces {@link FileMeta} for the regular files it contains.
 *
 * <p>Single responsibility: it only <i>reads</i> the filesystem. It never moves,
 * classifies or judges anything.
 *
 * <p>By design it scans the <b>top level only</b> (non-recursive) and skips
 * sub-directories. For a "tidy this folder" tool this is the safe default: we never
 * pull files out of folders the user already organized, and we never accidentally
 * sweep an entire directory tree.
 */
public final class FileScanner {

    /** Our own metadata directory, never surfaced as an organizable file. */
    public static final String METADATA_DIR = ".filenest";

    /**
     * @param dir folder to scan
     * @return metadata for each regular file directly inside {@code dir}, sorted by name
     * @throws IOException if the directory cannot be opened
     */
    public List<FileMeta> scan(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }
        List<FileMeta> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (shouldSkip(entry)) {
                    continue;
                }
                try {
                    FileMeta meta = FileMeta.of(entry);
                    if (!meta.directory()) {
                        files.add(meta);
                    }
                } catch (IOException perFile) {
                    // A single unreadable file must not abort the whole scan.
                    System.err.println("[Scanner] skipping unreadable file " + entry + ": " + perFile.getMessage());
                }
            }
        }
        files.sort(Comparator.comparing(FileMeta::fileName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private boolean shouldSkip(Path entry) {
        Path name = entry.getFileName();
        return name == null || name.toString().equals(METADATA_DIR);
    }
}
