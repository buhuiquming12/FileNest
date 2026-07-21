package com.filenest.core.classifier;

import com.filenest.model.ActionType;
import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * The default, always-available classifier. Deterministic and offline: it decides a
 * file's home purely from its extension (or modified date, depending on the scheme).
 *
 * <p>Because it never fails and never needs the network, its output is the base plan the
 * whole system can fall back to when AI is unavailable (fault isolation).
 */
public final class ExtensionClassifier implements ClassifierStrategy {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private final ZoneId zone;

    public ExtensionClassifier() {
        this(ZoneId.systemDefault());
    }

    public ExtensionClassifier(ZoneId zone) {
        this.zone = zone;
    }

    @Override
    public Optional<FileAction> classify(FileMeta file, OrganizeContext context) {
        String folder;
        String reason;

        if (context.scheme() == OrganizeContext.Scheme.BY_DATE) {
            folder = MONTH.format(file.lastModified().atZone(zone));
            reason = "按修改日期归档: " + folder;
        } else {
            // BY_TYPE and BY_PROJECT both get a type-based deterministic base;
            // for BY_PROJECT the AI advisor refines it into project folders on top.
            String category = CategoryRules.categoryFor(file.extension());
            folder = category;
            reason = CategoryRules.isKnown(file.extension())
                    ? "扩展名规则: ." + file.extension() + " → " + category
                    : "未知扩展名 → " + CategoryRules.OTHERS;
        }

        Path target = context.rootDir().resolve(folder).resolve(file.fileName());
        if (target.equals(file.path())) {
            // Already where it belongs.
            return Optional.of(FileAction.rule(file.path(), target, ActionType.SKIP,
                    "已在目标位置", false));
        }
        return Optional.of(FileAction.rule(file.path(), target, ActionType.MOVE, reason, false));
    }
}
