package com.filenest.core.executor;

import com.filenest.core.log.OperationLog;
import com.filenest.model.FileAction;
import com.filenest.model.OperationBatch;
import com.filenest.model.OperationRecord;
import com.filenest.model.OrganizeResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The only component that actually touches the filesystem to move files. Everything it
 * does is recorded in the {@link OperationLog} so it can be reversed.
 *
 * <p>Safety guarantees:
 * <ul>
 *   <li>Never overwrites — conflicts are resolved by {@link ConflictPolicy} (skip/rename).</li>
 *   <li>Every successful move is logged as {@code from -> to} for exact-reverse undo.</li>
 *   <li>A failure on one file is reported and does not abort the rest of the batch.</li>
 * </ul>
 */
public final class FileExecutor {

    private final OperationLog log;

    public FileExecutor(OperationLog log) {
        this.log = log;
    }

    /**
     * Executes the given (already user-selected) actions.
     *
     * @return a result with counts, per-file messages, and the id of the undo batch
     */
    public OrganizeResult execute(List<FileAction> actions, ConflictPolicy policy) {
        OrganizeResult.Builder result = new OrganizeResult.Builder();
        List<OperationRecord> records = new ArrayList<>();

        for (FileAction action : actions) {
            if (action == null || !action.isEffective()) {
                result.skip("\u8df3\u8fc7\uff08\u65e0\u9700\u79fb\u52a8\uff09: "
                        + (action == null ? "null" : fileName(action.sourcePath())));
                continue;
            }
            try {
                SafeMove move = validateMove(action);
                Path source = move.source();
                Path target = move.target();
                Files.createDirectories(target.getParent());
                validateTargetParent(move.root(), target.getParent());

                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (policy == ConflictPolicy.SKIP) {
                        result.skip("\u76ee\u6807\u5df2\u5b58\u5728\uff0c\u8df3\u8fc7: " + relativeName(target));
                        continue;
                    }
                    target = deduplicate(target);
                }

                Files.move(source, target); // no REPLACE_EXISTING - never overwrite
                records.add(new OperationRecord(source, target, action.type()));
                result.succeed("\u79fb\u52a8: " + fileName(source) + " \u2192 " + relativeName(target));
            } catch (IOException | RuntimeException e) {
                result.fail("\u79fb\u52a8\u5931\u8d25 " + fileName(action.sourcePath()) + ": " + e.getMessage());
            }
        }

        if (!records.isEmpty()) {
            String batchId = "batch-" + Instant.now().toEpochMilli() + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
            log.append(new OperationBatch(batchId, Instant.now(), records));
            result.batchId(batchId);
        }
        return result.build();
    }

    private static SafeMove validateMove(FileAction action) throws IOException {
        if (action.sourcePath() == null || action.targetPath() == null) {
            throw new IOException("\u6e90\u8def\u5f84\u6216\u76ee\u6807\u8def\u5f84\u4e3a\u7a7a");
        }
        Path source = action.sourcePath().toAbsolutePath().normalize();
        Path target = action.targetPath().toAbsolutePath().normalize();
        Path root = source.getParent();
        if (root == null || isCriticalRoot(root)) {
            throw new IOException("\u62d2\u7edd\u5728\u7cfb\u7edf\u5173\u952e\u76ee\u5f55\u6267\u884c\u6574\u7406");
        }
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("\u6e90\u4e0d\u662f\u5b89\u5168\u7684\u5e38\u89c4\u6587\u4ef6\uff08\u53ef\u80fd\u662f\u7b26\u53f7\u94fe\u63a5\uff09");
        }
        String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(".") || name.equals("thumbs.db")
                || name.equals("desktop.ini") || name.equals("ntuser.dat")
                || Files.isHidden(source)) {
            throw new IOException("\u9690\u85cf\u6216\u7cfb\u7edf\u914d\u7f6e\u6587\u4ef6\u4ec5\u5141\u8bb8\u67e5\u770b");
        }
        if (!target.startsWith(root) || target.equals(source) || target.getParent() == null) {
            throw new IOException("\u76ee\u6807\u8def\u5f84\u8d85\u51fa\u6240\u9009\u76ee\u5f55");
        }
        Path relative = root.relativize(target);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (relative.getName(i).toString().equalsIgnoreCase(".filenest")) {
                throw new IOException("\u7981\u6b62\u5199\u5165 FileNest \u5143\u6570\u636e\u76ee\u5f55");
            }
        }
        validateTargetParent(root, target.getParent());
        return new SafeMove(source, target, root);
    }

    /** Ensures an existing symlink in the destination chain cannot redirect a move outside root. */
    private static void validateTargetParent(Path root, Path targetParent) throws IOException {
        Path realRoot = root.toRealPath();
        Path existing = targetParent;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || Files.isSymbolicLink(existing)) {
            throw new IOException("\u76ee\u6807\u76ee\u5f55\u94fe\u4e0d\u5b89\u5168");
        }
        Path realExisting = existing.toRealPath();
        if (!realExisting.startsWith(realRoot)) {
            throw new IOException("\u76ee\u6807\u76ee\u5f55\u901a\u8fc7\u94fe\u63a5\u8df3\u51fa\u4e86\u6240\u9009\u76ee\u5f55");
        }
        if (Files.exists(targetParent, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(targetParent)
                    || !targetParent.toRealPath().startsWith(realRoot)) {
                throw new IOException("\u76ee\u6807\u76ee\u5f55\u4e0d\u662f\u6240\u9009\u76ee\u5f55\u5185\u7684\u5b89\u5168\u6587\u4ef6\u5939");
            }
        }
    }

    private static boolean isCriticalRoot(Path root) {
        if (root.getParent() == null) return true;
        String home = System.getProperty("user.home");
        if (home != null) {
            try {
                if (root.equals(Path.of(home).toAbsolutePath().normalize())) return true;
            } catch (RuntimeException ignored) {
                // Ignore a malformed user.home value.
            }
        }
        for (String env : List.of("SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramData")) {
            String value = System.getenv(env);
            if (value == null || value.isBlank()) continue;
            try {
                Path protectedPath = Path.of(value).toAbsolutePath().normalize();
                if (root.equals(protectedPath) || root.startsWith(protectedPath)) return true;
            } catch (RuntimeException ignored) {
                // Ignore malformed environment values.
            }
        }
        return false;
    }

    private record SafeMove(Path source, Path target, Path root) { }

    /**
     * Reverses a previously-executed batch (each move played back as target -> source).
     * Does not itself create a new undo batch; the caller removes the batch from the log.
     */
    public OrganizeResult undo(OperationBatch batch, ConflictPolicy policy) {
        OrganizeResult.Builder result = new OrganizeResult.Builder();
        List<OperationRecord> records = batch.records();
        Set<Path> touchedDirs = new HashSet<>();

        // Reverse order so nested/sequential moves unwind cleanly.
        for (int i = records.size() - 1; i >= 0; i--) {
            OperationRecord rec = records.get(i);
            Path from = rec.to();
            Path to = rec.from();
            try {
                SafeUndo undo = validateUndo(rec);
                from = undo.current();
                to = undo.original();
                Files.createDirectories(to.getParent());
                Path restoreTo = to;
                if (Files.exists(restoreTo, LinkOption.NOFOLLOW_LINKS)) {
                    if (policy == ConflictPolicy.SKIP) {
                        result.skip("\u539f\u4f4d\u7f6e\u5df2\u88ab\u5360\u7528\uff0c\u8df3\u8fc7: " + relativeName(to));
                        continue;
                    }
                    restoreTo = deduplicate(to);
                }
                Files.move(from, restoreTo);
                touchedDirs.add(from.getParent());
                result.succeed("\u64a4\u9500: " + fileName(from) + " \u2192 " + to);
            } catch (IOException | RuntimeException e) {
                result.fail("\u64a4\u9500\u5931\u8d25 " + fileName(from) + ": " + e.getMessage());
            }
        }

        cleanupEmptyDirs(touchedDirs);
        return result.build();
    }

    private static SafeUndo validateUndo(OperationRecord record) throws IOException {
        if (record == null || record.from() == null || record.to() == null) {
            throw new IOException("\u64a4\u9500\u8bb0\u5f55\u8def\u5f84\u4e3a\u7a7a");
        }
        Path original = record.from().toAbsolutePath().normalize();
        Path current = record.to().toAbsolutePath().normalize();
        Path root = original.getParent();
        if (root == null || isCriticalRoot(root) || !current.startsWith(root)
                || current.equals(original)) {
            throw new IOException("\u64a4\u9500\u8bb0\u5f55\u8d85\u51fa\u539f\u6574\u7406\u76ee\u5f55");
        }
        if (Files.isSymbolicLink(current)
                || !Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("\u5f85\u64a4\u9500\u6587\u4ef6\u4e0d\u5b58\u5728\u6216\u5df2\u53d8\u4e3a\u7b26\u53f7\u94fe\u63a5");
        }
        Path relative = root.relativize(current);
        for (Path part : relative) {
            if (part.toString().equalsIgnoreCase(".filenest")) {
                throw new IOException("\u64a4\u9500\u8bb0\u5f55\u6307\u5411 FileNest \u5143\u6570\u636e\u76ee\u5f55");
            }
        }
        validateTargetParent(root, current.getParent());
        validateTargetParent(root, original.getParent());
        return new SafeUndo(current, original);
    }

    private record SafeUndo(Path current, Path original) { }

    /** Finds a non-conflicting sibling name like {@code name (1).ext}. */
    private static Path deduplicate(Path target) {
        Path dir = target.getParent();
        String fileName = target.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = (dot <= 0) ? fileName : fileName.substring(0, dot);
        String ext = (dot <= 0) ? "" : fileName.substring(dot);

        for (int i = 1; i < 10_000; i++) {
            Path candidate = dir.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        // Extremely unlikely; fall back to a unique suffix.
        return dir.resolve(base + " (" + UUID.randomUUID() + ")" + ext);
    }

    /** Best-effort removal of directories left empty after an undo. */
    private static void cleanupEmptyDirs(Set<Path> dirs) {
        for (Path dir : dirs) {
            try {
                if (dir != null && Files.isDirectory(dir) && isEmpty(dir)) {
                    Files.delete(dir);
                }
            } catch (IOException ignored) {
                // Non-critical: leaving an empty folder behind is harmless.
            }
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        }
    }

    private static String fileName(Path p) {
        if (p == null) return "null";
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString();
    }

    private static String relativeName(Path target) {
        Path parent = target.getParent();
        Path grand = parent == null ? null : parent.getFileName();
        return (grand == null ? "" : grand + "/") + fileName(target);
    }
}
