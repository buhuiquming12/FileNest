package com.filenest.core.storage;

import java.nio.file.Path;

/** Read-only recommendation. Displaying this object never deletes anything. */
public record CleanupSuggestion(Path path, long bytes, Decision decision, String reason,
                                double confidence, String source) {
    public CleanupSuggestion {
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reason = reason == null ? "" : reason;
        source = source == null ? "" : source;
    }

    public enum Decision {
        DELETE("可删除"), REVIEW("建议检查"), KEEP("建议保留");
        private final String label;
        Decision(String label) { this.label = label; }
        public String label() { return label; }
    }
}
