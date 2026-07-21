package com.filenest.core.executor;

import com.filenest.core.log.JsonOperationLog;
import com.filenest.model.ActionType;
import com.filenest.model.FileAction;
import com.filenest.model.OperationBatch;
import com.filenest.model.OrganizeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileExecutorTest {

    @Test
    void movesFileLogsBatchAndUndoRestores(@TempDir Path dir) throws IOException {
        JsonOperationLog log = new JsonOperationLog(dir.resolve("ops.json"));
        FileExecutor exec = new FileExecutor(log);

        Path src = Files.writeString(dir.resolve("a.txt"), "hi");
        Path target = dir.resolve("文档").resolve("a.txt");
        FileAction move = FileAction.rule(src, target, ActionType.MOVE, "rule", false);

        OrganizeResult r = exec.execute(List.of(move), ConflictPolicy.RENAME);

        assertEquals(1, r.succeeded());
        assertTrue(Files.exists(target));
        assertFalse(Files.exists(src));
        assertNotNull(r.batchId());
        assertEquals(1, log.history().size());

        OperationBatch batch = log.last().orElseThrow();
        OrganizeResult undo = exec.undo(batch, ConflictPolicy.RENAME);

        assertEquals(1, undo.succeeded());
        assertTrue(Files.exists(src), "undo must restore the original file");
        assertFalse(Files.exists(target));
    }

    @Test
    void conflictNeverOverwritesExistingFile(@TempDir Path dir) throws IOException {
        JsonOperationLog log = new JsonOperationLog(dir.resolve("ops.json"));
        FileExecutor exec = new FileExecutor(log);

        Path src = Files.writeString(dir.resolve("a.txt"), "NEW");
        Path targetDir = Files.createDirectories(dir.resolve("docs"));
        Path target = Files.writeString(targetDir.resolve("a.txt"), "OLD");

        FileAction move = FileAction.rule(src, target, ActionType.MOVE, "rule", false);
        OrganizeResult r = exec.execute(List.of(move), ConflictPolicy.RENAME);

        assertEquals(1, r.succeeded());
        assertEquals("OLD", Files.readString(target), "existing file must be preserved");
        assertEquals("NEW", Files.readString(targetDir.resolve("a (1).txt")), "moved under a new name");
        assertFalse(Files.exists(src));
    }

    @Test
    void skipPolicyLeavesSourceInPlaceOnConflict(@TempDir Path dir) throws IOException {
        JsonOperationLog log = new JsonOperationLog(dir.resolve("ops.json"));
        FileExecutor exec = new FileExecutor(log);

        Path src = Files.writeString(dir.resolve("a.txt"), "NEW");
        Path targetDir = Files.createDirectories(dir.resolve("docs"));
        Path target = Files.writeString(targetDir.resolve("a.txt"), "OLD");

        FileAction move = FileAction.rule(src, target, ActionType.MOVE, "rule", false);
        OrganizeResult r = exec.execute(List.of(move), ConflictPolicy.SKIP);

        assertEquals(1, r.skipped());
        assertTrue(Files.exists(src), "source stays put when skipping a conflict");
        assertEquals("OLD", Files.readString(target));
    }
}
