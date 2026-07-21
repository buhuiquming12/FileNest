package com.filenest.ui;

import com.filenest.model.FileAction;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.nio.file.Path;

/**
 * View-model wrapping one {@link FileAction} for the plan table. It adds the mutable
 * "selected" checkbox state and pre-formats fields for display, keeping the UI ignorant of
 * the underlying model shape.
 *
 * <p>Default selection realizes the safety rule: only deterministic, effective actions are
 * pre-checked; anything requiring confirmation (all AI suggestions, duplicate moves) starts
 * <b>unchecked</b> so the user must opt in.
 */
public final class ActionRow {

    private final FileAction action;
    private final Path rootDir;
    private final BooleanProperty selected;

    public ActionRow(FileAction action, Path rootDir) {
        this.action = action;
        this.rootDir = rootDir;
        this.selected = new SimpleBooleanProperty(action.isEffective() && !action.requiresConfirm());
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    public FileAction action() {
        return action;
    }

    public String getFileName() {
        Path name = action.sourcePath().getFileName();
        return name == null ? action.sourcePath().toString() : name.toString();
    }

    public String getTarget() {
        if (!action.isEffective()) {
            return "（不移动）";
        }
        try {
            return rootDir.relativize(action.targetPath()).toString();
        } catch (RuntimeException e) {
            return action.targetPath().toString();
        }
    }

    public String getReason() {
        return action.reason();
    }

    public String getSource() {
        return action.source() == FileAction.Source.AI ? "AI" : "规则";
    }

    public double getConfidence() {
        return action.confidence();
    }

    public boolean requiresConfirm() {
        return action.requiresConfirm();
    }

    public boolean isEffective() {
        return action.isEffective();
    }
}
