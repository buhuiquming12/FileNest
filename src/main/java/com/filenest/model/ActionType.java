package com.filenest.model;

/**
 * What a {@link FileAction} does to a file.
 *
 * <ul>
 *   <li>{@link #MOVE}   move the file into a target folder</li>
 *   <li>{@link #RENAME} keep it in place but change its name</li>
 *   <li>{@link #SKIP}   do nothing — carries information/observation only</li>
 * </ul>
 */
public enum ActionType {
    MOVE,
    RENAME,
    SKIP
}
