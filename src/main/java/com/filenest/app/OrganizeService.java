package com.filenest.app;

import com.filenest.core.advisor.AiAdvisor;
import com.filenest.core.advisor.HeuristicAiAdvisor;
import com.filenest.core.advisor.NoOpAiAdvisor;
import com.filenest.core.advisor.TimeoutAiAdvisor;
import com.filenest.core.classifier.CategoryRules;
import com.filenest.core.classifier.ClassifierStrategy;
import com.filenest.core.classifier.ExtensionClassifier;
import com.filenest.core.duplicate.DuplicateDetector;
import com.filenest.core.executor.ConflictPolicy;
import com.filenest.core.executor.FileExecutor;
import com.filenest.core.log.JsonOperationLog;
import com.filenest.core.log.OperationLog;
import com.filenest.model.ActionType;
import com.filenest.model.DuplicateGroup;
import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OperationBatch;
import com.filenest.model.OrganizeContext;
import com.filenest.model.OrganizePlan;
import com.filenest.model.OrganizeResult;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The application-layer façade that orchestrates the whole flow and is the <i>only</i>
 * thing the UI talks to (least-knowledge principle): scan → rule-classify → dedupe →
 * AI-suggest → merge/sort → plan; then execute selected actions and support undo.
 *
 * <p>The two information sources — deterministic {@link ClassifierStrategy} and the
 * {@link AiAdvisor} — are combined here, not chained. If AI yields nothing (offline,
 * timeout, error) the rule plan still stands: fault isolation by construction.
 *
 * <p>All collaborators are injected (dependency inversion); {@link #createDefault()} wires
 * the standard offline-capable configuration.
 */
public final class OrganizeService {

    /** Folder that redundant exact-duplicate copies are proposed to move into. */
    public static final String DUPLICATES_FOLDER = "_重复文件";

    private final com.filenest.core.scanner.FileScanner scanner;
    private final ClassifierStrategy classifier;
    private final DuplicateDetector duplicateDetector;
    private final AiAdvisor advisor;
    private final FileExecutor executor;
    private final OperationLog log;

    public OrganizeService(com.filenest.core.scanner.FileScanner scanner,
                           ClassifierStrategy classifier,
                           DuplicateDetector duplicateDetector,
                           AiAdvisor advisor,
                           FileExecutor executor,
                           OperationLog log) {
        this.scanner = scanner;
        this.classifier = classifier;
        this.duplicateDetector = duplicateDetector;
        this.advisor = advisor;
        this.executor = executor;
        this.log = log;
    }

    /** Builds the standard wiring: extension rules + local heuristic AI (timeout-guarded) + JSON log. */
    public static OrganizeService createDefault() {
        OperationLog log = JsonOperationLog.atDefaultLocation();
        AiAdvisor ai = new TimeoutAiAdvisor(
                new HeuristicAiAdvisor(), new NoOpAiAdvisor(), Duration.ofSeconds(5));
        return new OrganizeService(
                new com.filenest.core.scanner.FileScanner(),
                new ExtensionClassifier(),
                new DuplicateDetector(),
                ai,
                new FileExecutor(log),
                log);
    }

    // ---- planning ---------------------------------------------------------------------

    /**
     * Produces a proposed plan for the folder in {@code context}. Read-only: nothing on
     * disk is changed here.
     */
    public OrganizePlan plan(OrganizeContext context) throws IOException {
        List<FileMeta> files = scanner.scan(context.rootDir());

        // Keyed by source path so every file has exactly one proposed action.
        Map<Path, FileAction> byPath = new LinkedHashMap<>();

        // 1. Deterministic base — always available.
        for (FileMeta f : files) {
            classifier.classify(f, context).ifPresent(a -> byPath.put(f.path(), a));
        }

        // 2. Exact duplicates (deterministic) override the redundant copies.
        for (DuplicateGroup group : duplicateDetector.detect(files)) {
            for (FileMeta dup : group.duplicates()) {
                Path target = context.rootDir().resolve(DUPLICATES_FOLDER).resolve(dup.fileName());
                String reason = "重复文件：与「" + group.keeper().fileName() + "」内容完全相同";
                byPath.put(dup.path(),
                        FileAction.rule(dup.path(), target, ActionType.MOVE, reason, true));
            }
        }

        // 3. AI suggestions, merged in without ever silently overriding a good rule.
        for (FileAction ai : advisor.suggest(files, context)) {
            FileAction existing = byPath.get(ai.sourcePath());
            if (existing == null || shouldPreferAi(existing, ai, context)) {
                byPath.put(ai.sourcePath(), ai);
            } else {
                byPath.put(ai.sourcePath(), existing.appendReason(ai.reason()));
            }
        }

        List<FileAction> actions = new ArrayList<>(byPath.values());
        actions.sort(planOrder());
        return new OrganizePlan(actions, Instant.now());
    }

    /**
     * Decides whether an AI suggestion should replace the current (rule) action, versus
     * merely annotate it. AI never replaces a confident, meaningful rule move except where
     * the user explicitly asked for AI-led organization (by-project).
     */
    private boolean shouldPreferAi(FileAction rule, FileAction ai, OrganizeContext context) {
        if (ai.type() == ActionType.SKIP) {
            return false; // an informational note never replaces a real move
        }
        if (rule.type() == ActionType.SKIP) {
            return true;  // the rule had no move opinion
        }
        if (context.scheme() == OrganizeContext.Scheme.BY_PROJECT) {
            return ai.confidence() >= 0.5; // project grouping is intentionally AI-led
        }
        // Otherwise only when the rule could not meaningfully classify the file.
        return isOthersTarget(rule) && ai.confidence() >= 0.5;
    }

    private boolean isOthersTarget(FileAction action) {
        Path parent = action.targetPath().getParent();
        Path folder = parent == null ? null : parent.getFileName();
        return folder != null && folder.toString().equals(CategoryRules.OTHERS);
    }

    /** Groups the preview by resulting folder so the user can see the new structure; notes last. */
    private Comparator<FileAction> planOrder() {
        return Comparator
                .comparing((FileAction a) -> a.isEffective() ? 0 : 1)
                .thenComparing(this::targetFolderKey)
                .thenComparing(a -> a.sourcePath().getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
    }

    private String targetFolderKey(FileAction a) {
        if (!a.isEffective()) {
            return "~"; // sorts after real folders
        }
        Path parent = a.targetPath().getParent();
        return parent == null ? "" : parent.toString();
    }

    // ---- execution & undo -------------------------------------------------------------

    /** Executes the user-selected subset of a plan. */
    public OrganizeResult execute(List<FileAction> selectedActions, ConflictPolicy policy) {
        return executor.execute(selectedActions, policy);
    }

    /** True when there is at least one batch that can be undone. */
    public boolean canUndo() {
        return log.last().isPresent();
    }

    /** Undoes the most recent executed batch. */
    public OrganizeResult undoLast(ConflictPolicy policy) {
        Optional<OperationBatch> last = log.last();
        if (last.isEmpty()) {
            return new OrganizeResult(0, 0, 0, List.of("没有可撤销的整理记录"), null);
        }
        OrganizeResult result = executor.undo(last.get(), policy);
        log.remove(last.get().id());
        return result;
    }

    /** Undoes a specific batch by id. */
    public OrganizeResult undo(String batchId, ConflictPolicy policy) {
        Optional<OperationBatch> batch = log.find(batchId);
        if (batch.isEmpty()) {
            return new OrganizeResult(0, 0, 0, List.of("找不到批次: " + batchId), null);
        }
        OrganizeResult result = executor.undo(batch.get(), policy);
        log.remove(batchId);
        return result;
    }

    public List<OperationBatch> history() {
        return log.history();
    }

    // ---- status (for the UI) ----------------------------------------------------------

    public String advisorName() {
        return advisor.name();
    }

    public boolean advisorAvailable() {
        return advisor.available();
    }
}
