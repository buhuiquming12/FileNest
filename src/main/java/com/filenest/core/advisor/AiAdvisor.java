package com.filenest.core.advisor;

import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;

import java.util.List;

/**
 * The pluggable AI seam (dependency inversion). The application layer depends only on
 * this interface; whether suggestions come from a local heuristic, a large language model
 * or nothing at all is an implementation detail that can be swapped without touching the
 * UI or the execution flow.
 *
 * <p><b>Contract:</b> an advisor only ever <i>suggests</i>. Every {@link FileAction} it
 * returns is {@code source == AI} and therefore {@code requiresConfirm == true}. It must
 * be side-effect free — it never touches the filesystem.
 */
public interface AiAdvisor {

    /**
     * @param files   the files under consideration
     * @param context how the user wants the folder organized
     * @return zero or more AI-sourced suggestions; never {@code null}
     */
    List<FileAction> suggest(List<FileMeta> files, OrganizeContext context);

    /** Short human-readable name for logs/UI. */
    default String name() {
        return getClass().getSimpleName();
    }

    /** Whether this advisor can currently produce suggestions (false = degraded mode). */
    default boolean available() {
        return true;
    }
}
