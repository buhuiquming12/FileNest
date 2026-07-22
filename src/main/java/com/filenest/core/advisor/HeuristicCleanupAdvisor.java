package com.filenest.core.advisor;

import com.filenest.core.storage.CleanupSuggestion;
import com.filenest.core.storage.FolderUsage;
import com.filenest.core.storage.LargeFileUsage;
import com.filenest.core.storage.StorageScanResult;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservative offline cleanup suggestions based on rebuildable folders and old temporary files. */
public final class HeuristicCleanupAdvisor implements CleanupAdvisor {
    private static final Set<String> CACHE_NAMES = Set.of("cache", "caches", ".cache", "tmp", "temp");
    private static final Set<String> BUILD_NAMES = Set.of("target", "build", "dist", "out", "node_modules", ".gradle");
    private static final Set<String> TEMP_EXTENSIONS = Set.of("tmp", "temp", "bak", "old", "dmp", "log");

    @Override
    public List<CleanupSuggestion> suggest(StorageScanResult scan) {
        List<CleanupSuggestion> result = new ArrayList<>();
        Set<Path> included = new HashSet<>();
        for (FolderUsage folder : scan.folders()) {
            if (folder.rootFiles() || folder.path().getFileName() == null) continue;
            String name = folder.path().getFileName().toString().toLowerCase(Locale.ROOT);
            if (CACHE_NAMES.contains(name)) {
                result.add(new CleanupSuggestion(folder.path(), folder.bytes(),
                        CleanupSuggestion.Decision.DELETE,
                        "缓存/临时目录通常可删除；请先关闭正在使用该目录的程序", 0.88, name()));
                included.add(folder.path());
            } else if (BUILD_NAMES.contains(name)) {
                result.add(new CleanupSuggestion(folder.path(), folder.bytes(),
                        CleanupSuggestion.Decision.REVIEW,
                        "疑似依赖或构建产物，可在确认能重新生成/下载后删除", 0.78, name()));
                included.add(folder.path());
            }
        }

        Instant oldEnough = Instant.now().minus(Duration.ofDays(30));
        for (LargeFileUsage file : scan.largestFiles()) {
            if (included.stream().anyMatch(folder -> file.path().startsWith(folder))) continue;
            String ext = extension(file.path());
            if (TEMP_EXTENSIONS.contains(ext) && file.lastModified().isBefore(oldEnough)) {
                result.add(new CleanupSuggestion(file.path(), file.bytes(),
                        CleanupSuggestion.Decision.DELETE,
                        "超过 30 天的临时/日志/备份文件；删除前确认不再用于恢复或排障", 0.82, name()));
            } else if (file.bytes() >= 500L * 1024 * 1024) {
                result.add(new CleanupSuggestion(file.path(), file.bytes(),
                        CleanupSuggestion.Decision.REVIEW,
                        "大文件占用较高，请确认是否仍需要，或迁移到其他磁盘/归档", 0.68, name()));
            }
        }
        result.sort((a, b) -> Long.compare(b.bytes(), a.bytes()));
        return result;
    }

    private String extension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    @Override
    public String name() {
        return "本地智能建议";
    }
}
