package com.filenest.ui;

import com.filenest.core.storage.CleanupCandidate;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/** Mutable selection wrapper used only by the cleanup preview table. */
final class CleanupRow {
    private final CleanupCandidate candidate;
    private final BooleanProperty selected;

    CleanupRow(CleanupCandidate candidate) {
        this.candidate = candidate;
        this.selected = new SimpleBooleanProperty(candidate.recommended());
    }

    CleanupCandidate candidate() { return candidate; }
    BooleanProperty selectedProperty() { return selected; }
    boolean isSelected() { return selected.get(); }
    void setSelected(boolean value) { selected.set(value); }
    String category() { return candidate.category(); }
    String name() { return candidate.path().getFileName().toString(); }
    String path() { return candidate.path().toString(); }
    long size() { return candidate.size(); }
}
