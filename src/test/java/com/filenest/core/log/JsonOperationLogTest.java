package com.filenest.core.log;

import com.filenest.model.ActionType;
import com.filenest.model.OperationBatch;
import com.filenest.model.OperationRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonOperationLogTest {

    @Test
    void persistsAndReloadsAcrossInstances(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("ops.json");
        OperationBatch batch = new OperationBatch("b1", Instant.parse("2026-01-01T00:00:00Z"),
                List.of(new OperationRecord(dir.resolve("a.txt"), dir.resolve("docs/a.txt"), ActionType.MOVE)));

        JsonOperationLog log = new JsonOperationLog(file);
        log.append(batch);
        assertTrue(Files.exists(file), "log file should be created (with parent dirs)");

        // A fresh instance must see the persisted data.
        JsonOperationLog reloaded = new JsonOperationLog(file);
        assertEquals(1, reloaded.history().size());
        OperationBatch got = reloaded.last().orElseThrow();
        assertEquals("b1", got.id());
        assertEquals(1, got.records().size());
        assertEquals(dir.resolve("a.txt"), got.records().get(0).from());
        assertEquals(ActionType.MOVE, got.records().get(0).type());
    }

    @Test
    void removeIsPersisted(@TempDir Path dir) {
        Path file = dir.resolve("ops.json");
        JsonOperationLog log = new JsonOperationLog(file);
        log.append(new OperationBatch("b1", Instant.now(),
                List.of(new OperationRecord(dir.resolve("a"), dir.resolve("b"), ActionType.MOVE))));

        log.remove("b1");
        assertTrue(log.history().isEmpty());
        assertTrue(new JsonOperationLog(file).history().isEmpty(), "removal must survive reload");
    }
}
