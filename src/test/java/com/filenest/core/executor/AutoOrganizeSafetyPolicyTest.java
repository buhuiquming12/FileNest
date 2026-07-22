package com.filenest.core.executor;

import com.filenest.model.ActionType;
import com.filenest.model.FileAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoOrganizeSafetyPolicyTest {
    private final AutoOrganizeSafetyPolicy policy = new AutoOrganizeSafetyPolicy();

    @Test
    void ruleModeAcceptsKnownTypeButRejectsUnknownAndConfirmationItems(@TempDir Path dir) throws IOException {
        Path known = Files.writeString(dir.resolve("note.txt"), "ok");
        Path unknown = Files.writeString(dir.resolve("blob.weird"), "unknown");
        FileAction knownMove = FileAction.rule(known, dir.resolve("\u6587\u6863/note.txt"),
                ActionType.MOVE, "rule", false);
        FileAction unknownMove = FileAction.rule(unknown, dir.resolve("\u5176\u4ed6/blob.weird"),
                ActionType.MOVE, "rule", false);
        FileAction duplicateLike = FileAction.rule(known, dir.resolve("_duplicates/note.txt"),
                ActionType.MOVE, "duplicate", true);

        AutoOrganizeSafetyPolicy.Selection selected = policy.select(
                List.of(knownMove, unknownMove, duplicateLike), dir, AutoOrganizeSafetyPolicy.Mode.RULES);

        assertEquals(List.of(knownMove), selected.actions());
        assertEquals(2, selected.rejectedCount());
    }

    @Test
    void aiModeRequiresHighConfidenceAndConfinedTarget(@TempDir Path dir) throws IOException {
        Path source = Files.writeString(dir.resolve("blob.weird"), "data");
        FileAction low = FileAction.ai(source, dir.resolve("Project/blob.weird"),
                ActionType.MOVE, "low", 0.79);
        FileAction high = FileAction.ai(source, dir.resolve("Project/blob.weird"),
                ActionType.MOVE, "high", 0.90);
        FileAction escaped = FileAction.ai(source, dir.resolveSibling("outside/blob.weird"),
                ActionType.MOVE, "escape", 0.99);

        assertFalse(policy.allowed(low, dir, AutoOrganizeSafetyPolicy.Mode.AI));
        assertTrue(policy.allowed(high, dir, AutoOrganizeSafetyPolicy.Mode.AI));
        assertFalse(policy.allowed(escaped, dir, AutoOrganizeSafetyPolicy.Mode.AI));
    }

    @Test
    void rejectsSymlinkSourceAndCriticalRoot(@TempDir Path dir) throws IOException {
        Path real = Files.writeString(dir.resolve("real.txt"), "data");
        Path link = dir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, real);
            FileAction linked = FileAction.rule(link, dir.resolve("docs/link.txt"),
                    ActionType.MOVE, "rule", false);
            assertFalse(policy.allowed(linked, dir, AutoOrganizeSafetyPolicy.Mode.RULES));
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // Symlink creation is not available on every Windows configuration.
        }

        Path critical = dir.getRoot();
        FileAction dummy = FileAction.rule(critical.resolve("a.txt"), critical.resolve("docs/a.txt"),
                ActionType.MOVE, "rule", false);
        assertFalse(policy.allowed(dummy, critical, AutoOrganizeSafetyPolicy.Mode.RULES));
    }
}
