package com.filenest.core.storage;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

/** Read-only recursive folder measurement that does not follow symbolic links. */
public final class FolderSizeService {

    public DirectoryStats measure(Path root) throws IOException {
        return scan(root, 0).total();
    }

    /**
     * Scans once and returns total usage, every direct child folder's recursive usage,
     * and up to {@code largestFileLimit} largest files. No cleanup advice is produced.
     */
    public StorageScanResult scan(Path root, int largestFileLimit) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            throw new IOException("目录不存在或不可访问: " + root);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        int limit = Math.max(0, largestFileLimit);
        MutableStats total = new MutableStats();
        MutableStats directFiles = new MutableStats();
        Map<Path, MutableStats> children = new LinkedHashMap<>();
        PriorityQueue<LargeFileUsage> largest = new PriorityQueue<>(
                Comparator.comparingLong(LargeFileUsage::bytes));

        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(normalizedRoot)) {
                    total.folderCount++;
                    Path child = directChild(normalizedRoot, dir);
                    MutableStats stats = children.computeIfAbsent(child, ignored -> new MutableStats());
                    if (!dir.equals(child)) {
                        stats.folderCount++;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                addFile(total, attrs.size());
                Path child = file.getParent().equals(normalizedRoot)
                        ? normalizedRoot : directChild(normalizedRoot, file);
                MutableStats bucket = child.equals(normalizedRoot)
                        ? directFiles : children.computeIfAbsent(child, ignored -> new MutableStats());
                addFile(bucket, attrs.size());
                if (limit > 0) {
                    largest.offer(new LargeFileUsage(file, attrs.size(), attrs.lastModifiedTime().toInstant()));
                    if (largest.size() > limit) {
                        largest.poll();
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                total.inaccessibleCount++;
                Path parent = file.toAbsolutePath().normalize().getParent();
                Path child = normalizedRoot.equals(parent) ? normalizedRoot : directChild(normalizedRoot, file);
                (child.equals(normalizedRoot) ? directFiles
                        : children.computeIfAbsent(child, ignored -> new MutableStats())).inaccessibleCount++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                if (exc != null) {
                    total.inaccessibleCount++;
                    Path child = directChild(normalizedRoot, dir);
                    (child.equals(normalizedRoot) ? directFiles
                            : children.computeIfAbsent(child, ignored -> new MutableStats())).inaccessibleCount++;
                }
                return FileVisitResult.CONTINUE;
            }
        });

        List<FolderUsage> folders = new ArrayList<>();
        if (directFiles.fileCount > 0 || directFiles.inaccessibleCount > 0) {
            folders.add(toUsage(normalizedRoot, directFiles, true));
        }
        children.forEach((path, stats) -> folders.add(toUsage(path, stats, false)));
        folders.sort(Comparator.comparingLong(FolderUsage::bytes).reversed()
                .thenComparing(f -> f.path().toString(), String.CASE_INSENSITIVE_ORDER));

        List<LargeFileUsage> largeFiles = new ArrayList<>(largest);
        largeFiles.sort(Comparator.comparingLong(LargeFileUsage::bytes).reversed());
        return new StorageScanResult(normalizedRoot, toDirectoryStats(total), folders,
                largeFiles, Instant.now());
    }

    private static Path directChild(Path root, Path item) {
        Path normalized = item.toAbsolutePath().normalize();
        if (normalized.equals(root)) {
            return root;
        }
        try {
            Path relative = root.relativize(normalized);
            return relative.getNameCount() == 0 ? root : root.resolve(relative.getName(0));
        } catch (IllegalArgumentException outsideRoot) {
            return root;
        }
    }

    private static void addFile(MutableStats stats, long bytes) {
        stats.bytes += Math.max(0, bytes);
        stats.fileCount++;
    }

    private static FolderUsage toUsage(Path path, MutableStats stats, boolean rootFiles) {
        return new FolderUsage(path, stats.bytes, stats.fileCount, stats.folderCount,
                stats.inaccessibleCount, rootFiles);
    }

    private static DirectoryStats toDirectoryStats(MutableStats stats) {
        return new DirectoryStats(stats.bytes, stats.fileCount, stats.folderCount,
                stats.inaccessibleCount);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    public record DirectoryStats(long bytes, long fileCount, long folderCount,
                                 long inaccessibleCount) {
        public String displayText() {
            String text = String.format("%s · %,d 个文件 · %,d 个子文件夹",
                    formatBytes(bytes), fileCount, folderCount);
            return inaccessibleCount == 0 ? text : text + " · " + inaccessibleCount + " 项无法读取";
        }
    }

    private static final class MutableStats {
        private long bytes;
        private long fileCount;
        private long folderCount;
        private long inaccessibleCount;
    }
}
