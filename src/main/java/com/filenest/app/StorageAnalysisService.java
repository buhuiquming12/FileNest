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
    private volatile AdviceRun lastAdviceRun = AdviceRun.localOnly(0);

    /** Details of the most recent cleanup-advice run, used to explain remote fallback. */
    public record AdviceRun(boolean remoteConfigured, boolean remoteSucceeded,
                            int localCount, int remoteCount, String error) {
        static AdviceRun localOnly(int localCount) {
            return new AdviceRun(false, false, localCount, 0, null);
        }
    }

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
        if (remote == null || !remote.available()) {
            lastAdviceRun = AdviceRun.localOnly(local.size());
            return local;
        }
        try {
            List<CleanupSuggestion> ai = remote.suggest(scan);
            Map<Path, CleanupSuggestion> merged = new LinkedHashMap<>();
            for (CleanupSuggestion item : local) merged.put(item.path().toAbsolutePath().normalize(), item);
            for (CleanupSuggestion item : ai) {
                Path path = item.path().toAbsolutePath().normalize();
                CleanupSuggestion existing = merged.get(path);
                merged.put(path, merge(existing, item));
            }
            lastAdviceRun = new AdviceRun(true, true, local.size(), ai.size(), null);
            return merged.values().stream().sorted((a, b) -> {
                int decision = Integer.compare(decisionRank(a.decision()), decisionRank(b.decision()));
                return decision != 0 ? decision : Long.compare(b.bytes(), a.bytes());
            }).toList();
        } catch (RuntimeException unavailable) {
            lastAdviceRun = new AdviceRun(true, false, local.size(), 0,
                    unavailable.getMessage() == null ? unavailable.getClass().getSimpleName()
                            : unavailable.getMessage());
            return local;
        }
    }

    static CleanupSuggestion merge(CleanupSuggestion local, CleanupSuggestion remote) {
        if (local == null) return remote;
        // Preserve the safer verdict while retaining both advisors' provenance.
        int remoteRank = decisionRank(remote.decision());
        int localRank = decisionRank(local.decision());
        CleanupSuggestion chosen = remoteRank > localRank ? remote : local;
        if (remoteRank == localRank && remote.confidence() > local.confidence()) {
            chosen = remote;
        }
        String reason = local.reason();
        if (!remote.reason().isBlank() && !remote.reason().equals(local.reason())) {
            reason = reason.isBlank() ? remote.reason() : reason + " | URL AI: " + remote.reason();
        }
        String source = local.source();
        if (!remote.source().isBlank() && !remote.source().equals(local.source())) {
            source = source.isBlank() ? remote.source() : source + " + " + remote.source();
        }
        return new CleanupSuggestion(chosen.path(), chosen.bytes(), chosen.decision(),
                reason, chosen.confidence(), source);
    }

    public AdviceRun lastAdviceRun() {
        return lastAdviceRun;
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
