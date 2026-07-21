package com.filenest.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of executing (or undoing) a plan — plain counts plus human-readable messages,
 * suitable for showing directly in the UI status area.
 */
public record OrganizeResult(
        int succeeded,
        int skipped,
        int failed,
        List<String> messages,
        String batchId   // id of the OperationBatch that can undo this run; null for undo results
) {
    public OrganizeResult {
        messages = List.copyOf(messages);
    }

    public int total() {
        return succeeded + skipped + failed;
    }

    /** Mutable accumulator used by the executor while it works through a plan. */
    public static final class Builder {
        private int succeeded;
        private int skipped;
        private int failed;
        private final List<String> messages = new ArrayList<>();
        private String batchId;

        public void succeed(String msg) {
            succeeded++;
            messages.add(msg);
        }

        public void skip(String msg) {
            skipped++;
            messages.add(msg);
        }

        public void fail(String msg) {
            failed++;
            messages.add(msg);
        }

        public void batchId(String id) {
            this.batchId = id;
        }

        public OrganizeResult build() {
            return new OrganizeResult(succeeded, skipped, failed, messages, batchId);
        }
    }
}
