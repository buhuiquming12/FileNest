package com.filenest.core.advisor;

import com.filenest.model.ActionType;
import com.filenest.model.FileAction;
import com.filenest.model.OrganizeContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeoutAiAdvisorTest {

    private final OrganizeContext ctx = OrganizeContext.byType(Path.of("."));

    @Test
    void slowAdvisorFallsBackInsteadOfBlocking() {
        AiAdvisor slow = (files, context) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(FileAction.ai(Path.of("x"), Path.of("y"), ActionType.MOVE, "late", 0.9));
        };

        TimeoutAiAdvisor guarded = new TimeoutAiAdvisor(slow, new NoOpAiAdvisor(), Duration.ofMillis(100));
        long start = System.nanoTime();
        List<FileAction> out = guarded.suggest(List.of(), ctx);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(out.isEmpty(), "timed-out advisor degrades to the fallback (no suggestions)");
        assertTrue(elapsedMs < 1500, "must not block for the full delegate duration");
        assertTrue(!guarded.lastRun().succeeded());
        assertTrue(guarded.lastRun().error().contains("timed out"));
    }

    @Test
    void throwingAdvisorFallsBackWithoutPropagating() {
        AiAdvisor broken = (files, context) -> {
            throw new IllegalStateException("boom");
        };
        TimeoutAiAdvisor guarded = new TimeoutAiAdvisor(broken, new NoOpAiAdvisor(), Duration.ofSeconds(1));

        assertTrue(guarded.suggest(List.of(), ctx).isEmpty());
        assertEquals("boom", guarded.lastRun().error());
    }

    @Test
    void fastAdvisorResultPassesThrough() {
        FileAction one = FileAction.ai(Path.of("a"), Path.of("b"), ActionType.MOVE, "quick", 0.8);
        AiAdvisor fast = (files, context) -> List.of(one);
        TimeoutAiAdvisor guarded = new TimeoutAiAdvisor(fast, new NoOpAiAdvisor(), Duration.ofSeconds(1));

        List<FileAction> out = guarded.suggest(List.of(), ctx);
        assertEquals(1, out.size());
        assertEquals("quick", out.get(0).reason());
        assertTrue(guarded.lastRun().succeeded());
    }
}
