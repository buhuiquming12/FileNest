package com.filenest.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * The full set of proposed actions for one organize session.
 *
 * <p>This is the only thing the UI receives from the application layer — it knows nothing
 * about scanning, rules or AI (least-knowledge principle). Immutable.
 */
public record OrganizePlan(List<FileAction> actions, Instant createdAt) {

    public OrganizePlan {
        actions = List.copyOf(actions);
    }

    public static OrganizePlan empty(Instant at) {
        return new OrganizePlan(Collections.emptyList(), at);
    }

    /** Actions that would actually move/rename a file. */
    public List<FileAction> effectiveActions() {
        return actions.stream().filter(FileAction::isEffective).toList();
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }
}
