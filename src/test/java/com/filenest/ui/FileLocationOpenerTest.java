package com.filenest.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileLocationOpenerTest {
    @TempDir Path temp;

    @Test
    void opensDirectoryItselfForFolderSuggestion() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("cache"));
        assertEquals(folder.toAbsolutePath().normalize(), FileLocationOpener.containingFolder(folder));
    }

    @Test
    void opensParentForFileOrMissingSuggestion() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("downloads"));
        Path file = Files.writeString(folder.resolve("old.zip"), "content");
        assertEquals(folder.toAbsolutePath().normalize(), FileLocationOpener.containingFolder(file));
        assertEquals(folder.toAbsolutePath().normalize(),
                FileLocationOpener.containingFolder(folder.resolve("already-removed.zip")));
    }
}
