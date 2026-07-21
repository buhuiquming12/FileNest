package com.filenest.model;

import java.nio.file.Path;

/**
 * Context handed to classifiers and advisors describing <i>how</i> the user wants
 * this folder organized. Kept small on purpose.
 *
 * @param rootDir the folder being organized; all target paths live under it
 * @param scheme  the organizing scheme the user picked
 */
public record OrganizeContext(Path rootDir, Scheme scheme) {

    /** Target folder layout the user is asking for. */
    public enum Scheme {
        /** Group by file type/category (Documents, Images, ...). The default. */
        BY_TYPE("按类型"),
        /** Group by last-modified date (YYYY/YYYY-MM). */
        BY_DATE("按日期"),
        /** Group by inferred project/topic (AI-assisted). */
        BY_PROJECT("按项目");

        private final String label;

        Scheme(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static OrganizeContext byType(Path rootDir) {
        return new OrganizeContext(rootDir, Scheme.BY_TYPE);
    }
}
