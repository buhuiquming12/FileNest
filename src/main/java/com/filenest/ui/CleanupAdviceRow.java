package com.filenest.ui;

import com.filenest.core.storage.CleanupSuggestion;
import com.filenest.core.storage.FolderSizeService;

import java.nio.file.Path;

/** Formatted read-only cleanup-advice row. */
public final class CleanupAdviceRow {
    private final CleanupSuggestion suggestion;

    public CleanupAdviceRow(CleanupSuggestion suggestion) {
        this.suggestion = suggestion;
    }

    public String getPath() {
        return getTargetPath().toString();
    }

    public Path getTargetPath() {
        return suggestion.path().toAbsolutePath().normalize();
    }
    public String getSize() { return FolderSizeService.formatBytes(suggestion.bytes()); }
    public String getDecision() { return suggestion.decision().label(); }
    public String getReason() { return suggestion.reason(); }
    public int getConfidence() { return (int) Math.round(suggestion.confidence() * 100); }
    public String getSource() { return suggestion.source(); }
}
