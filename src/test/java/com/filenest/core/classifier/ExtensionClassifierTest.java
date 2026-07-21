package com.filenest.core.classifier;

import com.filenest.model.ActionType;
import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExtensionClassifierTest {

    @Test
    void knownExtensionIsClassifiedByType(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("photo.JPG"), "x");
        FileAction a = new ExtensionClassifier()
                .classify(FileMeta.of(file), OrganizeContext.byType(dir))
                .orElseThrow();

        assertEquals(ActionType.MOVE, a.type());
        assertEquals(dir.resolve("图片").resolve("photo.JPG"), a.targetPath());
        assertEquals(1.0, a.confidence());
        assertFalse(a.requiresConfirm(), "deterministic rule moves should not need confirmation");
        assertEquals(FileAction.Source.RULE, a.source());
    }

    @Test
    void unknownExtensionFallsBackToOthers(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("mystery.zzz"), "x");
        FileAction a = new ExtensionClassifier()
                .classify(FileMeta.of(file), OrganizeContext.byType(dir))
                .orElseThrow();

        assertEquals(dir.resolve(CategoryRules.OTHERS).resolve("mystery.zzz"), a.targetPath());
    }

    @Test
    void byDateSchemeUsesMonthFolder(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("scan.pdf"), "x");
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2020-01-15T10:00:00Z")));

        FileAction a = new ExtensionClassifier(ZoneId.of("UTC"))
                .classify(FileMeta.of(file), new OrganizeContext(dir, OrganizeContext.Scheme.BY_DATE))
                .orElseThrow();

        assertEquals(dir.resolve("2020-01").resolve("scan.pdf"), a.targetPath());
    }
}
