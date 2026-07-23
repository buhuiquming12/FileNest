package com.filenest.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderSizeServiceTest {
    @TempDir Path temp;

    @Test
    void measuresNestedFilesWithoutChangingThem() throws Exception {
        Files.write(temp.resolve("a.bin"), new byte[10]);
        Path nested = Files.createDirectories(temp.resolve("one/two"));
        Files.write(nested.resolve("b.bin"), new byte[25]);

        FolderSizeService.DirectoryStats stats = new FolderSizeService().measure(temp);

        assertEquals(35, stats.bytes());
        assertEquals(2, stats.fileCount());
        assertEquals(2, stats.folderCount());
        assertEquals(0, stats.inaccessibleCount());
        assertTrue(Files.exists(nested.resolve("b.bin")));
    }

    @Test
    void scanReportsEachDirectFolderAndLargestFiles() throws Exception {
        Path first = Files.createDirectories(temp.resolve("first/nested"));
        Path second = Files.createDirectories(temp.resolve("second"));
        Files.write(first.resolve("a.bin"), new byte[30]);
        Files.write(second.resolve("b.bin"), new byte[12]);
        Files.write(temp.resolve("root.bin"), new byte[5]);

        StorageScanResult result = new FolderSizeService().scan(temp, 2);

        assertEquals(47, result.total().bytes());
        assertEquals("first", result.folders().get(0).path().getFileName().toString());
        assertEquals(30, result.folders().get(0).bytes());
        assertTrue(result.folders().stream().anyMatch(row -> row.rootFiles() && row.bytes() == 5));
        assertEquals(2, result.largestFiles().size());
        assertEquals(30, result.largestFiles().get(0).bytes());
    }

    @Test
    void scanRetainsNestedNotableFoldersForCleanupAnalysis() throws Exception {
        Path cache = Files.createDirectories(temp.resolve("users/alice/app/cache/deep"));
        Files.write(cache.resolve("entry.bin"), new byte[19]);

        StorageScanResult result = new FolderSizeService().scan(temp, 10);

        Path expected = temp.resolve("users/alice/app/cache").toAbsolutePath().normalize();
        FolderUsage sample = result.folderSamples().stream()
                .filter(folder -> folder.path().equals(expected)).findFirst().orElseThrow();
        assertEquals(19, sample.bytes());
        assertEquals(1, sample.fileCount());
        assertEquals(1, sample.folderCount());
    }

    @Test
    void scanReportsMonotonicProgressThroughCompletion() throws Exception {
        Files.write(Files.createDirectories(temp.resolve("first")).resolve("a.bin"), new byte[3]);
        Files.write(Files.createDirectories(temp.resolve("second")).resolve("b.bin"), new byte[4]);
        List<Double> progress = new ArrayList<>();

        new FolderSizeService().scan(temp, 2, progress::add);

        assertFalse(progress.isEmpty());
        assertEquals(0.0, progress.get(0));
        assertEquals(1.0, progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++) {
            assertTrue(progress.get(i) >= progress.get(i - 1), "progress must not move backwards");
        }
    }
}
