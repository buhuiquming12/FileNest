package com.filenest.core.log;

import com.filenest.model.OperationBatch;

import java.util.List;
import java.util.Optional;

/**
 * Durable record of executed move-batches, and the source of truth for undo.
 *
 * <p>Undo is reverse-replay of these records, never a guess about where a file "probably"
 * came from — which is why the log must persist across restarts.
 */
public interface OperationLog {

    /** Persists a newly executed batch. */
    void append(OperationBatch batch);

    /** All batches, oldest first. */
    List<OperationBatch> history();

    /** The most recent batch, if any. */
    Optional<OperationBatch> last();

    /** Looks up a batch by id. */
    Optional<OperationBatch> find(String batchId);

    /** Removes a batch (e.g. after it has been undone). */
    void remove(String batchId);
}
