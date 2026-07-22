package com.filenest.core.advisor;

import com.filenest.core.storage.CleanupSuggestion;
import com.filenest.core.storage.FolderSizeService;
import com.filenest.core.storage.StorageScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicCleanupAdvisorTest {
    @TempDir Path temp;

    @Test
    void recommendsKnownCacheButNeverDeletesIt() throws Exception {
        Path cache = Files.createDirectories(temp.resolve("cache"));
        Path data = cache.resolve("item.tmp");
        Files.write(data, new byte[20]);
        StorageScanResult scan = new FolderSizeService().scan(temp, 10);

        List<CleanupSuggestion> suggestions = new HeuristicCleanupAdvisor().suggest(scan);

        CleanupSuggestion item = suggestions.stream().filter(s -> s.path().equals(cache)).findFirst().orElseThrow();
        assertEquals(CleanupSuggestion.Decision.DELETE, item.decision());
        assertTrue(Files.exists(data), "advice must not mutate the filesystem");
    }

    @Test
    void findsNestedBuildFolderAndUsesAbsolutePaths() throws Exception {
        Path build = Files.createDirectories(temp.resolve("projects/demo/target/classes"));
        Files.write(build.resolve("app.bin"), new byte[24]);
        StorageScanResult scan = new FolderSizeService().scan(temp, 10);

        List<CleanupSuggestion> suggestions = new HeuristicCleanupAdvisor().suggest(scan);

        CleanupSuggestion item = suggestions.stream()
                .filter(suggestion -> suggestion.path().equals(temp.resolve("projects/demo/target").toAbsolutePath().normalize()))
                .findFirst().orElseThrow();
        assertEquals(CleanupSuggestion.Decision.REVIEW, item.decision());
        assertTrue(item.path().isAbsolute());
    }
}
