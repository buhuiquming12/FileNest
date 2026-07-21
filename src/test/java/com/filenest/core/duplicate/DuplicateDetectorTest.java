package com.filenest.core.duplicate;

import com.filenest.model.DuplicateGroup;
import com.filenest.model.FileMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuplicateDetectorTest {

    @Test
    void identicalContentIsGrouped(@TempDir Path dir) throws IOException {
        FileMeta a = FileMeta.of(Files.writeString(dir.resolve("a.txt"), "hello world"));
        FileMeta b = FileMeta.of(Files.writeString(dir.resolve("b.txt"), "hello world"));
        FileMeta c = FileMeta.of(Files.writeString(dir.resolve("c.txt"), "different"));

        List<DuplicateGroup> groups = new DuplicateDetector().detect(List.of(a, b, c));

        assertEquals(1, groups.size());
        DuplicateGroup g = groups.get(0);
        assertEquals(2, g.files().size());
        assertEquals(a.path(), g.keeper().path(), "first-scanned copy is the keeper");
        assertEquals(1, g.duplicates().size());
        assertEquals(b.path(), g.duplicates().get(0).path());
    }

    @Test
    void distinctContentProducesNoGroup(@TempDir Path dir) throws IOException {
        FileMeta a = FileMeta.of(Files.writeString(dir.resolve("a.txt"), "one"));
        FileMeta b = FileMeta.of(Files.writeString(dir.resolve("b.txt"), "two"));

        assertTrue(new DuplicateDetector().detect(List.of(a, b)).isEmpty());
    }
}
