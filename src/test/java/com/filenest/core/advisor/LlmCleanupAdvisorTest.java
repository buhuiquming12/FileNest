package com.filenest.core.advisor;

import com.filenest.core.storage.FolderSizeService;
import com.filenest.core.storage.FolderUsage;
import com.filenest.core.storage.LargeFileUsage;
import com.filenest.core.storage.StorageScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmCleanupAdvisorTest {
    @TempDir Path temp;

    @Test
    void boundsLargeInventoriesBeforeSendingThemToTheModel() {
        List<FolderUsage> top = folders("top", 100);
        List<FolderUsage> samples = folders("nested", 100);
        List<LargeFileUsage> files = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            files.add(new LargeFileUsage(temp.resolve("file-" + i + ".bin"), i, Instant.EPOCH));
        }
        StorageScanResult scan = new StorageScanResult(temp,
                new FolderSizeService.DirectoryStats(1000, 100, 200, 0),
                top, samples, files, Instant.EPOCH);

        String prompt = new LlmCleanupAdvisor("http://localhost", "", "model").buildPrompt(scan);

        assertEquals(75, prompt.lines().filter(line -> line.startsWith("DIR ")).count());
        assertEquals(45, prompt.lines().filter(line -> line.startsWith("FILE ")).count());
    }

    private List<FolderUsage> folders(String prefix, int count) {
        List<FolderUsage> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new FolderUsage(temp.resolve(prefix + "-" + i), i, i, 0, 0, false));
        }
        return result;
    }
}
