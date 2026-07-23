package com.filenest.core.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;

/** Read-only recursive folder measurement that does not follow symbolic links. */
public final class FolderSizeService {
    private static final int MAX_SCAN_THREADS = 4;
    private static final int LARGEST_FOLDER_LIMIT = 300;
    private static final int NOTABLE_FOLDER_LIMIT = 500;
    private static final Set<String> NOTABLE_FOLDER_NAMES = Set.of(
            "cache", "caches", ".cache", "code cache", "gpucache", "tmp", "temp",
            "crashdumps", "logs", "log", "$recycle.bin", "target", "build", "dist",
            "out", "node_modules", ".gradle", ".m2", ".nuget", ".next", ".parcel-cache");
    private static final Comparator<LargeFileUsage> FILE_SIZE_ORDER =
            Comparator.comparingLong(LargeFileUsage::bytes);
    private static final Comparator<FolderUsage> FOLDER_SIZE_ORDER =
            Comparator.comparingLong(FolderUsage::bytes);

    public DirectoryStats measure(Path root) throws IOException {
        return scan(root, 0).total();
    }

    /**
     * Scans direct child trees in parallel and returns total usage, direct child usage,
     * the largest nested folders, and up to {@code largestFileLimit} largest files.
     * Parallelism is intentionally bounded so a whole-drive scan remains responsive
     * without flooding a hard disk with random I/O. Symbolic links are never followed.
     */
    public StorageScanResult scan(Path root, int largestFileLimit) throws IOException {
        return scan(root, largestFileLimit, ignored -> { });
    }

    /**
     * Scans with a best-effort progress callback. Progress is reported after each direct
     * child tree completes, so callers can show a determinate bar without traversing twice.
     */
    public StorageScanResult scan(Path root, int largestFileLimit, DoubleConsumer progress) throws IOException {
        if (root == null || !Files.isDirectory(root)) {
            throw new IOException("目录不存在或不可访问: " + root);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        DoubleConsumer listener = progress == null ? ignored -> { } : progress;
        listener.accept(0.0);
        int fileLimit = Math.max(0, largestFileLimit);
        MutableStats total = new MutableStats();
        MutableStats directFiles = new MutableStats();
        List<Path> childDirectories = new ArrayList<>();
        PriorityQueue<LargeFileUsage> largestFiles = new PriorityQueue<>(FILE_SIZE_ORDER);

        // Enumerating the root once lets every direct child become an independent task.
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(normalizedRoot)) {
            for (Path entry : entries) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isDirectory()) {
                        childDirectories.add(entry.toAbsolutePath().normalize());
                    } else if (attrs.isRegularFile()) {
                        addFile(total, attrs.size());
                        addFile(directFiles, attrs.size());
                        offerFile(largestFiles,
                                new LargeFileUsage(entry.toAbsolutePath().normalize(), attrs.size(),
                                        attrs.lastModifiedTime().toInstant()), fileLimit);
                    }
                } catch (IOException | SecurityException inaccessible) {
                    total.inaccessibleCount++;
                    directFiles.inaccessibleCount++;
                }
            }
        }

        int threads = Math.max(1, Math.min(MAX_SCAN_THREADS,
                Math.min(childDirectories.size(), Runtime.getRuntime().availableProcessors())));
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "filenest-storage-scan");
            thread.setDaemon(true);
            return thread;
        });
        CompletionService<BranchResult> completions = new ExecutorCompletionService<>(executor);
        for (Path child : childDirectories) {
            completions.submit(() -> scanBranch(child, fileLimit));
        }
        executor.shutdown();

        List<FolderUsage> directFolders = new ArrayList<>();
        PriorityQueue<FolderUsage> largestFolders = new PriorityQueue<>(FOLDER_SIZE_ORDER);
        PriorityQueue<FolderUsage> notableFolders = new PriorityQueue<>(FOLDER_SIZE_ORDER);
        try {
            int completed = 0;
            int totalBranches = childDirectories.size();
            while (completed < totalBranches) {
                Future<BranchResult> future = completions.take();
                BranchResult branch = future.get();
                completed++;
                listener.accept(totalBranches == 0 ? 1.0 : completed / (double) totalBranches);
                total.merge(branch.stats());
                total.folderCount++; // The direct child itself is a folder under the root.
                FolderUsage direct = toUsage(branch.root(), branch.stats(), false);
                directFolders.add(direct);
                branch.largestFolders().forEach(folder ->
                        offerFolder(largestFolders, folder, LARGEST_FOLDER_LIMIT));
                branch.notableFolders().forEach(folder ->
                        offerFolder(notableFolders, folder, NOTABLE_FOLDER_LIMIT));
                branch.largestFiles().forEach(file -> offerFile(largestFiles, file, fileLimit));
            }
            if (totalBranches == 0) listener.accept(1.0);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            throw new IOException("扫描已中断", interrupted);
        } catch (ExecutionException failed) {
            executor.shutdownNow();
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("扫描目录失败: " + cause.getMessage(), cause);
        } finally {
            if (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        List<FolderUsage> folders = new ArrayList<>();
        if (directFiles.fileCount > 0 || directFiles.inaccessibleCount > 0) {
            folders.add(toUsage(normalizedRoot, directFiles, true));
        }
        folders.addAll(directFolders);
        folders.sort(Comparator.comparingLong(FolderUsage::bytes).reversed()
                .thenComparing(folder -> folder.path().toString(), String.CASE_INSENSITIVE_ORDER));

        java.util.Map<Path, FolderUsage> sampleByPath = new java.util.LinkedHashMap<>();
        largestFolders.forEach(folder -> sampleByPath.put(folder.path(), folder));
        notableFolders.forEach(folder -> sampleByPath.put(folder.path(), folder));
        List<FolderUsage> nestedFolderSamples = new ArrayList<>(sampleByPath.values());
        nestedFolderSamples.sort(Comparator.comparingLong(FolderUsage::bytes).reversed()
                .thenComparing(folder -> folder.path().toString(), String.CASE_INSENSITIVE_ORDER));
        List<LargeFileUsage> files = new ArrayList<>(largestFiles);
        files.sort(Comparator.comparingLong(LargeFileUsage::bytes).reversed()
                .thenComparing(file -> file.path().toString(), String.CASE_INSENSITIVE_ORDER));

        return new StorageScanResult(normalizedRoot, total.freeze(), folders,
                nestedFolderSamples, files, Instant.now());
    }

    private BranchResult scanBranch(Path branchRoot, int fileLimit) {
        PriorityQueue<LargeFileUsage> largestFiles = new PriorityQueue<>(FILE_SIZE_ORDER);
        PriorityQueue<FolderUsage> largestFolders = new PriorityQueue<>(FOLDER_SIZE_ORDER);
        PriorityQueue<FolderUsage> notableFolders = new PriorityQueue<>(FOLDER_SIZE_ORDER);
        Deque<DirectoryFrame> stack = new ArrayDeque<>();
        MutableStats[] rootStats = {null};

        try {
            Files.walkFileTree(branchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    stack.push(new DirectoryFrame(dir.toAbsolutePath().normalize(), new MutableStats()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || stack.isEmpty()) return FileVisitResult.CONTINUE;
                    addFile(stack.peek().stats(), attrs.size());
                    offerFile(largestFiles, file, attrs, fileLimit);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (!stack.isEmpty()) stack.peek().stats().inaccessibleCount++;
                    else if (rootStats[0] == null) {
                        rootStats[0] = new MutableStats();
                        rootStats[0].inaccessibleCount++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (stack.isEmpty()) return FileVisitResult.CONTINUE;
                    DirectoryFrame frame = stack.pop();
                    if (exc != null) frame.stats().inaccessibleCount++;
                    offerFolder(largestFolders, frame.path(), frame.stats(), LARGEST_FOLDER_LIMIT);
                    if (isNotableFolder(frame.path())) {
                        offerFolder(notableFolders, frame.path(), frame.stats(), NOTABLE_FOLDER_LIMIT);
                    }
                    if (stack.isEmpty()) {
                        rootStats[0] = frame.stats();
                    } else {
                        MutableStats parent = stack.peek().stats();
                        parent.merge(frame.stats());
                        parent.folderCount++;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException inaccessible) {
            if (rootStats[0] == null) rootStats[0] = new MutableStats();
            rootStats[0].inaccessibleCount++;
        }

        MutableStats stats = rootStats[0] == null ? new MutableStats() : rootStats[0];
        return new BranchResult(branchRoot, stats, List.copyOf(largestFolders),
                List.copyOf(notableFolders), List.copyOf(largestFiles));
    }

    private static boolean isNotableFolder(Path path) {
        Path name = path.getFileName();
        return name != null && NOTABLE_FOLDER_NAMES.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    private static void offerFile(PriorityQueue<LargeFileUsage> heap, Path path,
                                  BasicFileAttributes attrs, int limit) {
        if (limit <= 0 || (heap.size() >= limit && attrs.size() <= heap.peek().bytes())) return;
        offerFile(heap, new LargeFileUsage(path.toAbsolutePath().normalize(), attrs.size(),
                attrs.lastModifiedTime().toInstant()), limit);
    }

    private static void offerFile(PriorityQueue<LargeFileUsage> heap, LargeFileUsage file, int limit) {
        if (limit <= 0) return;
        heap.offer(file);
        if (heap.size() > limit) heap.poll();
    }

    private static void offerFolder(PriorityQueue<FolderUsage> heap, Path path,
                                    MutableStats stats, int limit) {
        if (stats.bytes <= 0 && stats.inaccessibleCount <= 0) return;
        if (heap.size() >= limit && stats.bytes <= heap.peek().bytes()) return;
        offerFolder(heap, toUsage(path, stats, false), limit);
    }

    private static void offerFolder(PriorityQueue<FolderUsage> heap, FolderUsage folder, int limit) {
        if (folder.bytes() <= 0 && folder.inaccessibleCount() <= 0) return;
        heap.offer(folder);
        if (heap.size() > limit) heap.poll();
    }

    private static void addFile(MutableStats stats, long bytes) {
        stats.bytes += bytes;
        stats.fileCount++;
    }

    private static FolderUsage toUsage(Path path, MutableStats stats, boolean rootFiles) {
        return new FolderUsage(path, stats.bytes, stats.fileCount, stats.folderCount,
                stats.inaccessibleCount, rootFiles);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    private static final class MutableStats {
        private long bytes;
        private long fileCount;
        private long folderCount;
        private long inaccessibleCount;

        private void merge(MutableStats other) {
            bytes += other.bytes;
            fileCount += other.fileCount;
            folderCount += other.folderCount;
            inaccessibleCount += other.inaccessibleCount;
        }

        private DirectoryStats freeze() {
            return new DirectoryStats(bytes, fileCount, folderCount, inaccessibleCount);
        }
    }

    private record DirectoryFrame(Path path, MutableStats stats) { }
    private record BranchResult(Path root, MutableStats stats, List<FolderUsage> largestFolders,
                                List<FolderUsage> notableFolders, List<LargeFileUsage> largestFiles) { }

    public record DirectoryStats(long bytes, long fileCount, long folderCount, long inaccessibleCount) {
        public String displayText() {
            String suffix = inaccessibleCount == 0 ? "" : "，" + inaccessibleCount + " 项无法访问";
            return formatBytes(bytes) + "（" + fileCount + " 个文件，" + folderCount + " 个文件夹" + suffix + "）";
        }
    }
}
