package com.filenest.core.scanner;

import com.filenest.model.FileMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileScannerTest {
    @Test
    void includesKnownUnknownNoExtensionAndDotFiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("note.txt"), "known");
        Files.writeString(dir.resolve("payload.unlisted_type"), "unknown");
        Files.writeString(dir.resolve("LICENSE"), "none");
        Files.writeString(dir.resolve(".env"), "hidden config");
        Files.createDirectory(dir.resolve("child"));

        List<FileMeta> files = new FileScanner().scan(dir);

        assertEquals(4, files.size());
        assertTrue(files.stream().anyMatch(f -> f.fileName().equals("payload.unlisted_type")));
        assertTrue(files.stream().anyMatch(f -> f.fileName().equals("LICENSE") && f.extension().isEmpty()));
        assertTrue(files.stream().anyMatch(f -> f.fileName().equals(".env")));
    }
}
