package com.filenest.core.classifier;

import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;

import java.util.Optional;

/**
 * A pluggable rule for turning one file into one proposed {@link FileAction}.
 *
 * <p>This is the open/closed seam of the classification layer: new ways of deciding a
 * file's home (by extension, by date, by regex, ...) are added as new strategies without
 * touching the orchestration flow.
 */
public interface ClassifierStrategy {

    /**
     * @return the proposed action for {@code file}, or empty if this strategy has no opinion.
     */
    Optional<FileAction> classify(FileMeta file, OrganizeContext context);

    /** Short human-readable name, used in logs/UI. */
    default String name() {
        return getClass().getSimpleName();
    }
}
