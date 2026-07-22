package com.filenest.core.advisor;

import com.filenest.core.storage.CleanupSuggestion;
import com.filenest.core.storage.StorageScanResult;

import java.util.List;

/** Independent cleanup-advice seam. Implementations only recommend and never delete. */
public interface CleanupAdvisor {
    List<CleanupSuggestion> suggest(StorageScanResult scan);
    default String name() { return getClass().getSimpleName(); }
    default boolean available() { return true; }
}
