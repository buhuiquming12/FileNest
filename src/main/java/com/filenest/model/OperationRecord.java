package com.filenest.model;

import java.nio.file.Path;

/**
 * One executed filesystem change, recorded so it can be undone by reverse-replay.
 *
 * <p>Undo is <i>not</i> guesswork: we remember exactly {@code from -> to} and simply
 * move it back.
 */
public record OperationRecord(Path from, Path to, ActionType type) {
}
