package com.filenest.model;

import java.time.Instant;
import java.util.List;

/**
 * All filesystem changes from a single "execute" click, grouped under one id so the
 * whole session can be undone atomically (as far as the filesystem allows).
 */
public record OperationBatch(String id, Instant executedAt, List<OperationRecord> records) {

    public OperationBatch {
        records = List.copyOf(records);
    }

    public int size() {
        return records.size();
    }
}
