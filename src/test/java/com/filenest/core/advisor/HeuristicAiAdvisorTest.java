package com.filenest.core.advisor;

import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicAiAdvisorTest {

    private final HeuristicAiAdvisor advisor = new HeuristicAiAdvisor();

    @Test
    void recognizesScreenshotAndAlwaysRequiresConfirmation(@TempDir Path dir) throws IOException {
        FileMeta shot = FileMeta.of(Files.writeString(dir.resolve("Screenshot_2024_01.png"), "x"));

        List<FileAction> out = advisor.suggest(List.of(shot), OrganizeContext.byType(dir));

        assertEquals(1, out.size());
        FileAction a = out.get(0);
        assertEquals(FileAction.Source.AI, a.source());
        assertTrue(a.requiresConfirm(), "every AI suggestion must require confirmation");
        assertTrue(a.confidence() < 1.0, "AI confidence is never certain");
        assertTrue(a.targetPath().toString().contains("截图"));
    }

    @Test
    void sniffsCsvContentDespiteMisleadingExtension(@TempDir Path dir) throws IOException {
        String csv = "name,age,city\nA,1,X\nB,2,Y\nC,3,Z\n";
        FileMeta f = FileMeta.of(Files.writeString(dir.resolve("export.txt"), csv));

        List<FileAction> out = advisor.suggest(List.of(f), OrganizeContext.byType(dir));

        assertEquals(1, out.size());
        assertTrue(out.get(0).targetPath().toString().contains("表格"),
                "content sniff should route CSV-like text to the spreadsheet folder");
    }

    @Test
    void normalizesVersionNoiseSoRelatedFilesShareAStem() {
        assertEquals(advisor.normalizeStem("报告v1"), advisor.normalizeStem("报告最终版"));
        assertEquals(advisor.normalizeStem("plan_draft"), advisor.normalizeStem("plan (2)"));
    }

    @Test
    void cleanFileGetsNoSuggestion(@TempDir Path dir) throws IOException {
        FileMeta f = FileMeta.of(Files.writeString(dir.resolve("budget.xlsx"), "x"));
        assertTrue(advisor.suggest(List.of(f), OrganizeContext.byType(dir)).isEmpty());
    }
}
