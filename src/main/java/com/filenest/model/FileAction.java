package com.filenest.model;

import java.nio.file.Path;

/**
 * A single proposed change to one file — the central contract of the whole system.
 *
 * <p>The UI displays it, the executor consumes it, the log records it. No layer needs
 * to know how another produced it (interface segregation + dependency inversion).
 *
 * <p><b>Safety invariant:</b> anything derived from AI ({@code source == Source.AI})
 * must have {@code requiresConfirm == true}. AI only ever <i>suggests</i>; a human is
 * the last gate before a file actually moves. Use {@link #ai} / {@link #rule} factories
 * so this invariant cannot be forgotten.
 *
 * @param sourcePath     current location of the file
 * @param targetPath     where it would end up (equal to sourcePath for SKIP)
 * @param type           MOVE / RENAME / SKIP
 * @param reason         human-readable justification (e.g. "扩展名规则: .jpg → 图片")
 * @param confidence     1.0 for deterministic rules; (0,1) for AI suggestions
 * @param requiresConfirm low-confidence or risky actions the user must explicitly approve
 * @param source         who proposed this action — a rule or the AI advisor
 */
public record FileAction(
        Path sourcePath,
        Path targetPath,
        ActionType type,
        String reason,
        double confidence,
        boolean requiresConfirm,
        Source source
) {
    /** Origin of an action. Kept on the action so the UI and merge logic can trust the safety invariant. */
    public enum Source {
        RULE,
        AI
    }

    public FileAction {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0,1]: " + confidence);
        }
        // Enforce the core safety boundary at construction time.
        if (source == Source.AI && !requiresConfirm) {
            requiresConfirm = true;
        }
    }

    /** Deterministic, rule-produced action (confidence 1.0). */
    public static FileAction rule(Path source, Path target, ActionType type, String reason, boolean requiresConfirm) {
        return new FileAction(source, target, type, reason, 1.0, requiresConfirm, Source.RULE);
    }

    /** AI-produced suggestion — always requires confirmation regardless of the flag passed. */
    public static FileAction ai(Path source, Path target, ActionType type, String reason, double confidence) {
        return new FileAction(source, target, type, reason, confidence, true, Source.AI);
    }

    /** A "do nothing but inform" action carrying an observation (e.g. an AI similarity note). */
    public static FileAction skip(Path source, String reason, double confidence, Source src) {
        return new FileAction(source, source, ActionType.SKIP, reason, confidence, src == Source.AI, src);
    }

    /** Returns a copy with a replaced reason. */
    public FileAction withReason(String newReason) {
        return new FileAction(sourcePath, targetPath, type, newReason, confidence, requiresConfirm, source);
    }

    /** Returns a copy with extra text appended to the reason (used when merging rule + AI notes). */
    public FileAction appendReason(String extra) {
        return withReason(reason + "  |  " + extra);
    }

    /** True when this action would actually touch the filesystem. */
    public boolean isEffective() {
        return type != ActionType.SKIP && !sourcePath.equals(targetPath);
    }
}
