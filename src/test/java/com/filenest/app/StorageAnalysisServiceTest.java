package com.filenest.app;

import com.filenest.core.storage.CleanupSuggestion;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageAnalysisServiceTest {

    @Test
    void keepsRemoteProvenanceWhenLocalVerdictWins() {
        Path path = Path.of("cache").toAbsolutePath().normalize();
        CleanupSuggestion local = new CleanupSuggestion(path, 100,
                CleanupSuggestion.Decision.REVIEW, "local safety reason", 0.9, "local advisor");
        CleanupSuggestion remote = new CleanupSuggestion(path, 100,
                CleanupSuggestion.Decision.DELETE, "remote model reason", 0.8, "URL AI test");

        CleanupSuggestion merged = StorageAnalysisService.merge(local, remote);

        assertEquals(CleanupSuggestion.Decision.REVIEW, merged.decision());
        assertTrue(merged.source().contains("local advisor"));
        assertTrue(merged.source().contains("URL AI test"));
        assertTrue(merged.reason().contains("remote model reason"));
    }
}
