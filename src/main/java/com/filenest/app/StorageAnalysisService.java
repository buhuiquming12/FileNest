package com.filenest.app;

import com.filenest.core.advisor.CleanupAdvisor;
import com.filenest.core.advisor.HeuristicCleanupAdvisor;
import com.filenest.core.advisor.LlmCleanupAdvisor;
import com.filenest.core.advisor.UrlModelCatalog;
import com.filenest.core.storage.CleanupSuggestion;
import com.filenest.core.storage.FolderSizeService;
import com.filenest.core.storage.StorageScanResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;

/** Keeps disk scanning, cleanup advice, and URL model discovery as separate operations. */
public final class StorageAnalysisService {
    private static final int LARGE_FILE_LIMIT = 500;
    private final FolderSizeService scanner;
    private final CleanupAdvisor localAdvisor;
    private final UrlModelCatalog modelCatalog;
    private volatile CleanupAdvisor remoteAdvisor;

    public StorageAnalysisService() {
        this(new FolderSizeService(), new HeuristicCleanupAdvisor(), new UrlModelCatalog());
    }

    StorageAnalysisService(FolderSizeService scanner, CleanupAdvisor localAdvisor,
                           UrlModelCatalog modelCatalog) {
        this.scanner = scanner;
        this.localAdvisor = localAdvisor;
        this.modelCatalog = modelCatalog;
    }

    /** Facts only: performs no AI request and produces no cleanup judgment. */
    public StorageScanResult scan(Path root) throws IOException {
        return scanner.scan(root, LARGE_FILE_LIMIT);
    }

    /** Scans storage facts and reports completed direct-directory work to the caller. */
    public StorageScanResult scan(Path root, DoubleConsumer progress) throws IOException {
        return scanner.scan(root, LARGE_FILE_LIMIT, progress);
    }

    /** Advice only: consumes a prior scan and performs no filesystem traversal. */
    public List<CleanupSuggestion> advise(StorageScanResult scan) {
        List<CleanupSuggestion> local = localAdvisor.suggest(scan);
        CleanupAdvisor remote = remoteAdvisor;
        if (remote == null || !remote.available()) return local;
        try {
            List<CleanupSuggestion> ai = remote.suggest(scan);
            if (ai.isEmpty()) return local;
            Map<Path, CleanupSuggestion> merged = new LinkedHashMap<>();
            for (CleanupSuggestion item : local) merged.put(item.path().toAbsolutePath().normalize(), item);
            for (CleanupSuggestion item : ai) {
                Path path = item.path().toAbsolutePath().normalize();
                CleanupSuggestion existing = merged.get(path);
                // The remote model may add context, but it may never weaken a local safety verdict.
                int aiRank = decisionRank(item.decision());
                int localRank = existing == null ? -1 : decisionRank(existing.decision());
                if (existing == null || aiRank > localRank
                        || (aiRank == localRank && item.confidence() > existing.confidence())) {
                    merged.put(path, item);
                }
            }
            return merged.values().stream().sorted((a, b) -> {
                int decision = Integer.compare(decisionRank(a.decision()), decisionRank(b.decision()));
                return decision != 0 ? decision : Long.compare(b.bytes(), a.bytes());
            }).toList();
        } catch (RuntimeException unavailable) {
            return local;
        }
    }

    private static int decisionRank(CleanupSuggestion.Decision decision) {
        return switch (decision) {
            case DELETE -> 0;
            case REVIEW -> 1;
            case KEEP -> 2;
        };
    }

    public synchronized void configureAi(String endpoint, String apiKey, String model) {
        remoteAdvisor = endpoint == null || endpoint.isBlank()
                ? null : new LlmCleanupAdvisor(endpoint, apiKey, model);
    }

    public List<String> fetchModels(String endpoint, String apiKey) throws Exception {
        return modelCatalog.fetch(endpoint, apiKey);
    }

    public String advisorName() {
        CleanupAdvisor remote = remoteAdvisor;
        return remote == null ? localAdvisor.name() : remote.name();
    }
}
