package com.filenest.core.executor;

/**
 * What to do when a move's target path is already occupied.
 *
 * <p>Note the deliberate absence of any "overwrite" option: silently replacing an existing
 * file could destroy user data, which violates the tool's first rule (never lose a file).
 */
public enum ConflictPolicy {

    /** Leave the source where it is and report the conflict. */
    SKIP,

    /** Move it anyway, under a de-duplicated name like {@code report (1).pdf}. The default. */
    RENAME
}
