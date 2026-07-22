package com.filenest.app;

import com.filenest.core.advisor.AiAdvisor;
import com.filenest.core.advisor.HeuristicAiAdvisor;
import com.filenest.core.advisor.NoOpAiAdvisor;
import com.filenest.core.advisor.TimeoutAiAdvisor;
import com.filenest.core.classifier.ExtensionClassifier;
import com.filenest.core.duplicate.DuplicateDetector;
import com.filenest.core.executor.ConflictPolicy;
import com.filenest.core.executor.FileExecutor;
import com.filenest.core.log.JsonOperationLog;
import com.filenest.core.log.OperationLog;
import com.filenest.core.scanner.FileScanner;
import com.filenest.model.FileAction;
import com.filenest.model.OrganizeContext;
import com.filenest.model.OrganizePlan;
import com.filenest.model.OrganizeResult;
import com.filenest.model.OrganizeScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizeServiceTest {

    private OrganizeService service(Path dir) {
        OperationLog log = new JsonOperationLog(dir.resolve(".filenest").resolve("ops.json"));
        return new OrganizeService(
                new FileScanner(),
                new ExtensionClassifier(),
                new DuplicateDetector(),
                new TimeoutAiAdvisor(new HeuristicAiAdvisor(), new NoOpAiAdvisor(), Duration.ofSeconds(5)),
                new FileExecutor(log),
                log);
    }

    @Test
    void planIsReadOnlyAndAiOnlySuggests(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("note.txt"), "hello");
        Files.writeString(dir.resolve("pic.jpg"), "img");

        OrganizePlan plan = service(dir).plan(OrganizeContext.byType(dir));

        // Nothing moved during planning.
        assertTrue(Files.exists(dir.resolve("note.txt")));
        assertTrue(Files.exists(dir.resolve("pic.jpg")));
        assertFalse(plan.actions().isEmpty());

        // Safety invariant: no AI action is auto-approved.
        assertTrue(plan.actions().stream()
                        .filter(a -> a.source() == FileAction.Source.AI)
                        .allMatch(FileAction::requiresConfirm),
                "AI actions must always require confirmation");
    }

    @Test
    void duplicatesAreDetectedAndFlaggedForConfirmation(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("report.dat"), "same-bytes");
        Files.writeString(dir.resolve("report-copy.dat"), "same-bytes");

        OrganizePlan plan = service(dir).plan(OrganizeContext.byType(dir));

        boolean hasDuplicateMove = plan.actions().stream()
                .anyMatch(a -> a.targetPath().toString().contains(OrganizeService.DUPLICATES_FOLDER)
                        && a.requiresConfirm());
        assertTrue(hasDuplicateMove, "one duplicate copy should be routed to the duplicates folder");
    }

    @Test
    void executeThenUndoRoundTripsFilesBack(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("note.txt"), "hello");
        Files.writeString(dir.resolve("pic.jpg"), "img");
        OrganizeService svc = service(dir);

        OrganizePlan plan = svc.plan(OrganizeContext.byType(dir));
        List<FileAction> autoSelected = plan.actions().stream()
                .filter(FileAction::isEffective)
                .filter(a -> !a.requiresConfirm())
                .toList();

        OrganizeResult exec = svc.execute(autoSelected, ConflictPolicy.RENAME);
        assertTrue(exec.succeeded() >= 2);
        assertTrue(Files.exists(dir.resolve("文档").resolve("note.txt")));
        assertTrue(Files.exists(dir.resolve("图片").resolve("pic.jpg")));
        assertTrue(svc.canUndo());

        OrganizeResult undo = svc.undoLast(ConflictPolicy.RENAME);
        assertTrue(undo.succeeded() >= 2);
        assertTrue(Files.exists(dir.resolve("note.txt")), "files return to their original location");
        assertTrue(Files.exists(dir.resolve("pic.jpg")));
        assertFalse(svc.canUndo(), "the undone batch is removed from history");
    }

    @Test
    void scanAndSuggestionAreSeparateOperations(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("note.txt"), "hello");
        AtomicInteger advisorCalls = new AtomicInteger();
        AiAdvisor countingAdvisor = (files, context) -> {
            advisorCalls.incrementAndGet();
            return List.of();
        };
        OperationLog log = new JsonOperationLog(dir.resolve(".filenest/ops.json"));
        OrganizeService service = new OrganizeService(new FileScanner(), new ExtensionClassifier(),
                new DuplicateDetector(), countingAdvisor, new FileExecutor(log), log);
        OrganizeContext context = OrganizeContext.byType(dir);

        OrganizeScan scan = service.scan(context);
        assertEquals(0, advisorCalls.get(), "scanning must not call an advisor");

        OrganizePlan plan = service.suggest(scan, context);
        assertEquals(1, advisorCalls.get());
        assertFalse(plan.isEmpty());
    }

    @Test
    void protectedFilesAreVisibleButNotSentToAdvisor(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".env"), "secret");
        Files.writeString(dir.resolve("note.txt"), "normal");
        AtomicInteger advisorFileCount = new AtomicInteger();
        AiAdvisor recordingAdvisor = (files, context) -> {
            advisorFileCount.set(files.size());
            return List.of();
        };
        OperationLog log = new JsonOperationLog(dir.resolve(".filenest/ops.json"));
        OrganizeService service = new OrganizeService(new FileScanner(), new ExtensionClassifier(),
                new DuplicateDetector(), recordingAdvisor, new FileExecutor(log), log);

        OrganizeScan scan = service.scan(OrganizeContext.byType(dir));
        assertEquals(2, scan.files().size(), "inventory must include protected files");
        OrganizePlan plan = service.suggest(scan, OrganizeContext.byType(dir));

        assertEquals(1, advisorFileCount.get(), "advisor must not receive protected files");
        FileAction protectedAction = plan.actions().stream()
                .filter(a -> a.sourcePath().getFileName().toString().equals(".env"))
                .findFirst().orElseThrow();
        assertFalse(protectedAction.isEffective());
    }
}
