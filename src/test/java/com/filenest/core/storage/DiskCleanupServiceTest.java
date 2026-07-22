package com.filenest.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskCleanupServiceTest {
    @TempDir Path temp;

    @Test
    void previewsAndDeletesOnlyItemsInsideScannedAllowList() throws Exception {
        Path allowed = Files.createDirectory(temp.resolve("safe-temp"));
        Path item = Files.write(allowed.resolve("old.tmp"), new byte[17]);
        DiskCleanupService service = new DiskCleanupService(temp,
                List.of(new DiskCleanupService.CleanupLocation(allowed, "test")));

        CleanupPreview preview = service.scan();
        assertEquals(1, preview.candidates().size());
        assertEquals(17, preview.reclaimableBytes());

        CleanupResult result = service.clean(preview.candidates());
        assertEquals(1, result.deleted());
        assertEquals(17, result.reclaimedBytes());
        assertFalse(Files.exists(item));
        assertTrue(Files.exists(allowed));
    }

    @Test
    void rejectsCandidateOutsideAllowList() throws Exception {
        Path allowed = Files.createDirectory(temp.resolve("safe-temp"));
        Path outside = Files.write(temp.resolve("keep.txt"), new byte[9]);
        DiskCleanupService service = new DiskCleanupService(temp,
                List.of(new DiskCleanupService.CleanupLocation(allowed, "test")));
        service.scan();

        CleanupCandidate forged = new CleanupCandidate(outside, "test", 9,
                Instant.now(), false, true);
        CleanupResult result = service.clean(List.of(forged));

        assertEquals(0, result.deleted());
        assertEquals(1, result.failed());
        assertTrue(Files.exists(outside));
    }
}
