package com.filenest.core.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans a deliberately small allow-list of disposable Windows locations and deletes only
 * candidates returned by that scan. It never follows symbolic links and never deletes an
 * allow-list root itself.
 */
public final class DiskCleanupService {
    private static final Duration RECOMMENDED_AGE = Duration.ofHours(24);

    private final Path systemDrive;
    private final List<CleanupLocation> locations;
    private volatile List<Path> lastAllowedRoots = List.of();

    public DiskCleanupService() {
        this(defaultSystemDrive(), defaultLocations(defaultSystemDrive()));
    }

    DiskCleanupService(Path systemDrive, List<CleanupLocation> locations) {
        this.systemDrive = systemDrive.toAbsolutePath().normalize();
        this.locations = List.copyOf(locations);
    }

    public CleanupPreview scan() throws IOException {
        List<CleanupCandidate> candidates = new ArrayList<>();
        List<Path> allowed = new ArrayList<>();
        int inaccessible = 0;

        for (CleanupLocation location : locations) {
            Path root = location.path().toAbsolutePath().normalize();
            if (!isOnSystemDrive(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            allowed.add(root);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path entry : entries) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(entry,
                                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        EntryStats stats = measureEntry(entry, attrs);
                        inaccessible += stats.inaccessible();
                        boolean recommended = stats.inaccessible() == 0
                                && stats.newestModified().isBefore(Instant.now().minus(RECOMMENDED_AGE));
                        candidates.add(new CleanupCandidate(entry.toAbsolutePath().normalize(),
                                location.category(), stats.size(), stats.newestModified(),
                                attrs.isDirectory(), recommended));
                    } catch (IOException | SecurityException ex) {
                        inaccessible++;
                    }
                }
            } catch (IOException | SecurityException ex) {
                inaccessible++;
            }
        }

        lastAllowedRoots = List.copyOf(allowed);
        candidates.sort(Comparator.comparingLong(CleanupCandidate::size).reversed());
        FileStore store = Files.getFileStore(systemDrive);
        return new CleanupPreview(systemDrive, store.getTotalSpace(), store.getUsableSpace(),
                List.copyOf(candidates), inaccessible);
    }

    public CleanupResult clean(List<CleanupCandidate> selected) {
        if (selected == null || selected.isEmpty()) {
            return new CleanupResult(0, 0, 0, List.of("没有选择清理项。"));
        }
        long reclaimed = 0;
        int deleted = 0;
        int failed = 0;
        List<String> messages = new ArrayList<>();
        for (CleanupCandidate candidate : selected) {
            Path path = candidate.path().toAbsolutePath().normalize();
            try {
                ensureAllowed(path);
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    messages.add("已不存在，跳过: " + path);
                    continue;
                }
                deleteEntry(path);
                reclaimed += candidate.size();
                deleted++;
            } catch (IOException | SecurityException ex) {
                failed++;
                messages.add("无法删除 " + path + ": " + ex.getMessage());
            }
        }
        return new CleanupResult(reclaimed, deleted, failed, List.copyOf(messages));
    }

    private void ensureAllowed(Path candidate) throws IOException {
        boolean allowed = lastAllowedRoots.stream().anyMatch(root ->
                !candidate.equals(root) && candidate.startsWith(root));
        if (!allowed) {
            throw new IOException("拒绝删除不在本次安全扫描范围内的路径");
        }
    }

    private void deleteEntry(Path entry) throws IOException {
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(entry)) {
            try (var paths = Files.walk(entry)) {
                List<Path> ordered = paths.sorted(Comparator.reverseOrder()).toList();
                for (Path path : ordered) {
                    Files.deleteIfExists(path);
                }
            }
        } else {
            Files.deleteIfExists(entry);
        }
    }

    private EntryStats measureEntry(Path entry, BasicFileAttributes attrs) throws IOException {
        if (!attrs.isDirectory() || Files.isSymbolicLink(entry)) {
            return new EntryStats(attrs.isRegularFile() ? attrs.size() : 0,
                    attrs.lastModifiedTime().toInstant(), 0);
        }
        MutableEntryStats stats = new MutableEntryStats(attrs.lastModifiedTime().toInstant());
        Files.walkFileTree(entry, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes current) {
                stats.newest(current.lastModifiedTime().toInstant());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes current) {
                if (current.isRegularFile()) {
                    stats.size += current.size();
                }
                stats.newest(current.lastModifiedTime().toInstant());
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                stats.inaccessible++;
                return FileVisitResult.CONTINUE;
            }
        });
        return new EntryStats(stats.size, stats.newestModified, stats.inaccessible);
    }

    private record EntryStats(long size, Instant newestModified, int inaccessible) { }

    private static final class MutableEntryStats {
        private long size;
        private Instant newestModified;
        private int inaccessible;

        private MutableEntryStats(Instant newestModified) {
            this.newestModified = newestModified;
        }

        private void newest(Instant value) {
            if (value.isAfter(newestModified)) {
                newestModified = value;
            }
        }
    }

    private boolean isOnSystemDrive(Path path) {
        Path root = path.getRoot();
        Path systemRoot = systemDrive.getRoot();
        return root != null && systemRoot != null
                && root.toString().equalsIgnoreCase(systemRoot.toString());
    }

    private static Path defaultSystemDrive() {
        String drive = System.getenv().getOrDefault("SystemDrive", "C:");
        if (!drive.endsWith("\\") && !drive.endsWith("/")) {
            drive += "\\";
        }
        return Paths.get(drive);
    }

    private static List<CleanupLocation> defaultLocations(Path drive) {
        Map<Path, String> unique = new LinkedHashMap<>();
        addEnvLocation(unique, "TEMP", "用户临时文件");
        addEnvLocation(unique, "TMP", "用户临时文件");
        addEnvChild(unique, "LOCALAPPDATA", "Temp", "用户临时文件");
        addEnvChild(unique, "LOCALAPPDATA", "CrashDumps", "应用崩溃转储");

        Path root = drive.toAbsolutePath().normalize();
        unique.put(root.resolve("Windows").resolve("Temp"), "Windows 临时文件");
        unique.put(root.resolve("Windows").resolve("SoftwareDistribution").resolve("Download"),
                "Windows 更新下载缓存");

        List<CleanupLocation> result = new ArrayList<>();
        unique.forEach((path, category) -> result.add(new CleanupLocation(path, category)));
        return result;
    }

    private static void addEnvLocation(Map<Path, String> paths, String env, String category) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            paths.put(Paths.get(value).toAbsolutePath().normalize(), category);
        }
    }

    private static void addEnvChild(Map<Path, String> paths, String env, String child,
                                    String category) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            paths.put(Paths.get(value).resolve(child).toAbsolutePath().normalize(), category);
        }
    }

    record CleanupLocation(Path path, String category) { }
}
