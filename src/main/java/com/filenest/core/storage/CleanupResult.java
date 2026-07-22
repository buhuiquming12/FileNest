package com.filenest.core.storage;

import java.util.List;

/** Summary returned after deleting user-confirmed cleanup candidates. */
public record CleanupResult(long reclaimedBytes, int deleted, int failed, List<String> messages) { }
