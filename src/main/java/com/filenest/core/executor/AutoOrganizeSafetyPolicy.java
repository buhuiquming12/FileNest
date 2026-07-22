package com.filenest.core.executor;

import com.filenest.core.classifier.CategoryRules;
import com.filenest.model.FileAction;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Conservative gate used only by one-click automatic organization. */
public final class AutoOrganizeSafetyPolicy {
    public static final double MIN_AI_CONFIDENCE = 0.80;
    public static final int MAX_AUTO_ACTIONS = 500;

    public enum Mode { RULES, AI }

    public Selection select(List<FileAction> actions, Path root, Mode mode) {
        if (actions == null || root == null || mode == null) {
            throw new IllegalArgumentException("actions, root and mode must not be null");
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<FileAction> safe = new ArrayList<>();
        int rejected = 0;
        for (FileAction action : actions) {
            if (safe.size() >= MAX_AUTO_ACTIONS || !allowed(action, normalizedRoot, mode)) {
                rejected++;
            } else {
                safe.add(action);
            }
        }
        return new Selection(safe, rejected);
    }

    public boolean allowed(FileAction action, Path root, Mode mode) {
        if (root == null || mode == null) return false;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (isCriticalRoot(normalizedRoot) || action == null || action.sourcePath() == null
                || action.targetPath() == null || !action.isEffective()) return false;
        if (mode == Mode.RULES) {
            if (action.source() != FileAction.Source.RULE || action.requiresConfirm()) return false;
        } else if (action.source() != FileAction.Source.AI
                || action.confidence() < MIN_AI_CONFIDENCE) {
            return false;
        }

        Path source = action.sourcePath().toAbsolutePath().normalize();
        Path target = action.targetPath().toAbsolutePath().normalize();
        if (!normalizedRoot.equals(source.getParent()) || !target.startsWith(normalizedRoot)
                || target.equals(source) || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (Files.isHidden(source)) return false;
        } catch (Exception cannotCheck) {
            return false;
        }
        String originalName = source.getFileName().toString();
        String sourceName = originalName.toLowerCase(Locale.ROOT);
        if (sourceName.startsWith(".") || sourceName.equals("thumbs.db")
                || sourceName.equals("desktop.ini") || sourceName.equals("ntuser.dat")) {
            return false;
        }
        if (mode == Mode.RULES
                && !CategoryRules.isKnown(com.filenest.model.FileMeta.extensionOf(originalName))) {
            return false;
        }
        Path relativeTarget = normalizedRoot.relativize(target);
        if (relativeTarget.getNameCount() < 2 || relativeTarget.getNameCount() > 6
                || !target.getFileName().equals(source.getFileName())) {
            return false;
        }
        String firstFolder = relativeTarget.getName(0).toString();
        for (int i = 0; i < relativeTarget.getNameCount() - 1; i++) {
            String folder = relativeTarget.getName(i).toString();
            if (folder.startsWith(".") || folder.equalsIgnoreCase(".filenest")) return false;
        }
        return mode != Mode.RULES || !firstFolder.equals(CategoryRules.OTHERS);
    }

    private boolean isCriticalRoot(Path root) {
        if (root.getParent() == null) return true;
        String home = System.getProperty("user.home");
        if (home != null) {
            try {
                if (root.equals(Path.of(home).toAbsolutePath().normalize())) return true;
            } catch (RuntimeException ignored) {
                // Ignore a malformed user.home value.
            }
        }
        for (String env : List.of("SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramData")) {
            String value = System.getenv(env);
            if (value != null && !value.isBlank()) {
                try {
                    Path protectedPath = Path.of(value).toAbsolutePath().normalize();
                    if (root.equals(protectedPath) || root.startsWith(protectedPath)) return true;
                } catch (RuntimeException ignored) {
                    // Ignore malformed environment values; the remaining checks still apply.
                }
            }
        }
        return false;
    }

    public record Selection(List<FileAction> actions, int rejectedCount) {
        public Selection { actions = List.copyOf(actions); }
    }
}
