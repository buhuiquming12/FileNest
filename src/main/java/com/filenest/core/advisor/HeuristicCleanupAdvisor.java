package com.filenest.core.advisor;

import com.filenest.core.storage.CleanupSuggestion;
import com.filenest.core.storage.FolderUsage;
import com.filenest.core.storage.LargeFileUsage;
import com.filenest.core.storage.StorageScanResult;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative, actionable offline cleanup suggestions built from the complete scan summary. */
public final class HeuristicCleanupAdvisor implements CleanupAdvisor {
    private static final long MB = 1024L * 1024;
    private static final long GB = 1024L * MB;
    private static final Set<String> CACHE_NAMES = Set.of(
            "cache", "caches", ".cache", "code cache", "gpucache", "tmp", "temp",
            "$recycle.bin", "crashdumps");
    private static final Set<String> BUILD_NAMES = Set.of(
            "target", "build", "dist", "out", "node_modules", ".next", ".parcel-cache");
    private static final Set<String> PACKAGE_CACHE_NAMES = Set.of(".gradle", ".m2", ".nuget");
    private static final Set<String> LOG_NAMES = Set.of("log", "logs");
    private static final Set<String> TEMP_EXTENSIONS = Set.of("tmp", "temp", "bak", "old", "dmp", "log");
    private static final Set<String> ARCHIVE_INSTALLER_EXTENSIONS = Set.of(
            "zip", "7z", "rar", "iso", "img", "exe", "msi", "msix", "appx");
    private static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "mp4", "mkv", "mov", "avi", "wmv", "flac", "wav", "psd");
    private static final Set<String> VIRTUAL_DISK_EXTENSIONS = Set.of("vhd", "vhdx", "vmdk", "qcow2");
    private static final Set<String> SYSTEM_ROOT_NAMES = Set.of(
            "windows", "program files", "program files (x86)", "programdata", "recovery",
            "system volume information");
    private static final Set<String> MANAGED_SYSTEM_FILES = Set.of(
            "pagefile.sys", "hiberfil.sys", "swapfile.sys", "memory.dmp");

    @Override
    public List<CleanupSuggestion> suggest(StorageScanResult scan) {
        Map<Path, CleanupSuggestion> suggestions = new LinkedHashMap<>();
        Set<Path> coveredFolders = new LinkedHashSet<>();

        // Direct protected roots are shown explicitly so users know what must not be manually removed.
        for (FolderUsage folder : scan.folders()) {
            if (folder.rootFiles() || folder.path().getFileName() == null) continue;
            String name = lowerName(folder.path());
            if (SYSTEM_ROOT_NAMES.contains(name)) {
                put(suggestions, new CleanupSuggestion(folder.path(), folder.bytes(),
                        CleanupSuggestion.Decision.KEEP,
                        "系统或应用目录，不要直接删除；请改用 Windows“存储/临时文件”或程序自带卸载器", 0.98, name()));
            }
        }

        // The scan includes large nested folders plus notable cache/build folder names from any depth.
        for (FolderUsage folder : scan.folderSamples()) {
            if (folder.rootFiles() || folder.path().getFileName() == null) continue;
            Path path = normalize(folder.path());
            String name = lowerName(path);
            boolean systemPath = isSystemPath(scan.root(), path);
            if (CACHE_NAMES.contains(name)) {
                CleanupSuggestion.Decision decision = systemPath
                        ? CleanupSuggestion.Decision.REVIEW : CleanupSuggestion.Decision.DELETE;
                String reason = systemPath
                        ? "系统区域中的缓存/临时目录；请通过 Windows“存储/临时文件”清理，不要直接删除整个目录"
                        : "缓存或临时目录，可在关闭相关程序后清理；若程序正在运行，跳过被占用文件";
                put(suggestions, new CleanupSuggestion(path, folder.bytes(), decision, reason,
                        systemPath ? 0.76 : 0.91, name()));
                coveredFolders.add(path);
            } else if (BUILD_NAMES.contains(name)) {
                put(suggestions, new CleanupSuggestion(path, folder.bytes(),
                        CleanupSuggestion.Decision.REVIEW,
                        "依赖或构建产物；确认项目仍可重新构建/下载依赖后再删，首次重建会耗时和联网", 0.86, name()));
                coveredFolders.add(path);
            } else if (PACKAGE_CACHE_NAMES.contains(name)) {
                put(suggestions, new CleanupSuggestion(path, folder.bytes(),
                        CleanupSuggestion.Decision.REVIEW,
                        "开发工具依赖缓存，可释放空间但之后需要重新下载；建议先用对应工具的缓存清理命令", 0.82, name()));
                coveredFolders.add(path);
            } else if (LOG_NAMES.contains(name) && folder.bytes() >= 100 * MB) {
                put(suggestions, new CleanupSuggestion(path, folder.bytes(),
                        CleanupSuggestion.Decision.REVIEW,
                        "日志目录占用较大；优先删除过期日志而非整个目录，并保留近期排障所需日志", 0.72, name()));
                coveredFolders.add(path);
            }
        }

        Instant thirtyDaysAgo = Instant.now().minus(Duration.ofDays(30));
        Instant ninetyDaysAgo = Instant.now().minus(Duration.ofDays(90));
        for (LargeFileUsage file : scan.largestFiles()) {
            Path path = normalize(file.path());
            if (coveredFolders.stream().anyMatch(path::startsWith)) continue;
            String name = lowerName(path);
            String ext = extension(path);
            boolean systemPath = isSystemPath(scan.root(), path);

            if (MANAGED_SYSTEM_FILES.contains(name)) {
                put(suggestions, new CleanupSuggestion(path, file.bytes(), CleanupSuggestion.Decision.KEEP,
                        "Windows 管理的系统文件，禁止直接删除；休眠文件只能通过关闭休眠功能释放", 0.99, name()));
            } else if (TEMP_EXTENSIONS.contains(ext) && file.lastModified().isBefore(thirtyDaysAgo)) {
                put(suggestions, new CleanupSuggestion(path, file.bytes(),
                        systemPath ? CleanupSuggestion.Decision.REVIEW : CleanupSuggestion.Decision.DELETE,
                        systemPath
                                ? "超过 30 天的系统日志/转储/临时文件；请使用系统清理工具或确认用途后处理"
                                : "超过 30 天的临时、日志、备份或转储文件；确认不再用于恢复/排障后可删除",
                        systemPath ? 0.72 : 0.86, name()));
            } else if (isDownloadsPath(scan.root(), path)
                    && ARCHIVE_INSTALLER_EXTENSIONS.contains(ext)
                    && file.lastModified().isBefore(ninetyDaysAgo)) {
                put(suggestions, new CleanupSuggestion(path, file.bytes(), CleanupSuggestion.Decision.REVIEW,
                        "下载目录中超过 90 天的安装包/压缩包；确认软件已安装且无需留档后删除", 0.84, name()));
            } else if (VIRTUAL_DISK_EXTENSIONS.contains(ext)) {
                put(suggestions, new CleanupSuggestion(path, file.bytes(), CleanupSuggestion.Decision.REVIEW,
                        "虚拟磁盘镜像通常很大且可能包含完整环境；先在虚拟机/容器工具中确认未使用，再迁移或删除", 0.78, name()));
            } else if (MEDIA_EXTENSIONS.contains(ext) && file.bytes() >= GB) {
                put(suggestions, new CleanupSuggestion(path, file.bytes(), CleanupSuggestion.Decision.REVIEW,
                        "大型媒体/工程文件；如需保留，优先迁移到其他磁盘、NAS 或归档存储", 0.74, name()));
            } else if (ARCHIVE_INSTALLER_EXTENSIONS.contains(ext)
                    && file.bytes() >= 100 * MB && file.lastModified().isBefore(ninetyDaysAgo)) {
                put(suggestions, new CleanupSuggestion(path, file.bytes(), CleanupSuggestion.Decision.REVIEW,
                        "较旧的大型安装包、镜像或压缩包；核对是否已有备份、是否仍需重装后处理", 0.72, name()));
            } else if (file.bytes() >= 500 * MB) {
                put(suggestions, new CleanupSuggestion(path, file.bytes(), CleanupSuggestion.Decision.REVIEW,
                        "大文件占用较高；确认最近使用情况，按需删除、压缩，或迁移到其他磁盘", 0.68, name()));
            }
        }

        List<CleanupSuggestion> result = new ArrayList<>(suggestions.values());
        result.sort((a, b) -> {
            int decision = Integer.compare(priority(a.decision()), priority(b.decision()));
            return decision != 0 ? decision : Long.compare(b.bytes(), a.bytes());
        });
        return result;
    }

    private static void put(Map<Path, CleanupSuggestion> suggestions, CleanupSuggestion suggestion) {
        suggestions.putIfAbsent(normalize(suggestion.path()), suggestion);
    }

    private static int priority(CleanupSuggestion.Decision decision) {
        return switch (decision) {
            case DELETE -> 0;
            case REVIEW -> 1;
            case KEEP -> 2;
        };
    }

    private static boolean isDownloadsPath(Path root, Path path) {
        String relative = relativeLower(root, path);
        return relative.contains("\\downloads\\") || relative.startsWith("downloads\\")
                || relative.contains("\\下载\\") || relative.startsWith("下载\\");
    }

    private static boolean isSystemPath(Path root, Path path) {
        try {
            Path relative = normalize(root).relativize(normalize(path));
            return relative.getNameCount() > 0
                    && SYSTEM_ROOT_NAMES.contains(relative.getName(0).toString().toLowerCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String relativeLower(Path root, Path path) {
        try { return normalize(root).relativize(normalize(path)).toString().toLowerCase(Locale.ROOT); }
        catch (RuntimeException ex) { return path.toString().toLowerCase(Locale.ROOT); }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String lowerName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
    }

    private static String extension(Path path) {
        String name = lowerName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    @Override
    public String name() {
        return "本地智能建议";
    }
}
