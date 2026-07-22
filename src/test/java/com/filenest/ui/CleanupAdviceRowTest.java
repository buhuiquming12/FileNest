package com.filenest.ui;

import com.filenest.core.storage.CleanupSuggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CleanupAdviceRowTest {
    @TempDir Path temp;

    @Test
    void displaysNormalizedAbsolutePathInsteadOfRelativePath() {
        Path candidate = temp.resolve("nested/cache");
        CleanupSuggestion suggestion = new CleanupSuggestion(candidate, 10,
                CleanupSuggestion.Decision.REVIEW, "reason", 0.8, "test");

        CleanupAdviceRow row = new CleanupAdviceRow(suggestion);

        assertEquals(candidate.toAbsolutePath().normalize().toString(), row.getPath());
    }
}
