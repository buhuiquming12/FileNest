package com.filenest.core.executor;

import com.filenest.core.log.OperationLog;
import com.filenest.model.FileAction;
import com.filenest.model.OperationBatch;
import com.filenest.model.OperationRecord;
import com.filenest.model.OrganizeResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
            if (!action.isEffective()) {
                result.skip("跳过（无需移动）: " + fileName(action.sourcePath()));
                continue;
            }
            try {
                Path source = action.sourcePath();
                if (!Files.exists(source)) {
                    result.fail("源文件不存在: " + source);
                    continue;
                }
                Path target = action.targetPath();
                Files.createDirectories(target.getParent());

                if (Files.exists(target)) {
                    if (policy == ConflictPolicy.SKIP) {
                        result.skip("目标已存在，跳过: " + relativeName(target));
                        continue;
                    }
                    target = deduplicate(target);
                }

                Files.move(source, target); // no REPLACE_EXISTING — never overwrite
                records.add(new OperationRecord(source, target, action.type()));
                result.succeed("移动: " + fileName(source) + " → " + relativeName(target));
            } catch (IOException e) {
                result.fail("移动失败 " + fileName(action.sourcePath()) + ": " + e.getMessage());
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
            Path from = rec.to();     // where the file currently is
            Path to = rec.from();     // where it originally came from
            try {
                if (!Files.exists(from)) {
                    result.skip("已不在原处，跳过撤销: " + from);
                    continue;
                }
                Files.createDirectories(to.getParent());
                Path restoreTo = to;
                if (Files.exists(restoreTo)) {
                    if (policy == ConflictPolicy.SKIP) {
                        result.skip("原位置已被占用，跳过: " + relativeName(to));
                        continue;
                    }
                    restoreTo = deduplicate(to);
                }
                Files.move(from, restoreTo);
                touchedDirs.add(from.getParent());
                result.succeed("撤销: " + fileName(from) + " → " + to);
            } catch (IOException e) {
                result.fail("撤销失败 " + fileName(from) + ": " + e.getMessage());
            }
        }

        cleanupEmptyDirs(touchedDirs);
        return result.build();
    }

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
        Path name = p.getFileName();
        return name == null ? p.toString() : name.toString();
    }

    private static String relativeName(Path target) {
        Path parent = target.getParent();
        Path grand = parent == null ? null : parent.getFileName();
        return (grand == null ? "" : grand + "/") + fileName(target);
    }
}
