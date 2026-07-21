package com.filenest.core.advisor;

import com.filenest.model.ActionType;
import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A fully-offline, local "AI-style" advisor. It needs no network and no API key, so the
 * application is useful out of the box while the {@link AiAdvisor} interface stays ready
 * for a real model (see {@link LlmAiAdvisor}).
 *
 * <p>It covers the three AI features from the design, all as <i>suggestions</i>:
 * <ol>
 *   <li><b>Content-aware classification</b> — recognizes meaningful patterns in messy
 *       file names ({@code 截图}, {@code IMG_0001}, {@code 发票…}) and sniffs the first
 *       bytes of text/unknown files to catch misleading extensions.</li>
 *   <li><b>Similar-file hints</b> — clusters files whose names normalize to the same stem
 *       ({@code 报告v1} / {@code 报告最终版}) and flags them for human review. (Exact
 *       duplicates are handled deterministically elsewhere.)</li>
 *   <li><b>Structure proposal</b> — under the "by project" scheme, proposes grouping a
 *       cluster of related files under a common project folder.</li>
 * </ol>
 *
 * <p>Every returned action is AI-sourced and therefore requires confirmation. Confidence
 * is always &lt; 1.0 so the UI leaves these unchecked by default.
 */
public final class HeuristicAiAdvisor implements AiAdvisor {

    /** How many leading bytes we sniff from text/unknown files. */
    private static final int SNIFF_BYTES = 2048;
    private static final long MAX_SNIFF_SIZE = 5L * 1024 * 1024; // don't sniff huge files

    /** A name-pattern rule: if the file name matches, propose the given sub-folder. */
    private record NameRule(Pattern pattern, String[] folder, String reason, double confidence) {
    }

    private static final List<NameRule> NAME_RULES = List.of(
            new NameRule(Pattern.compile("(?i)(screen ?shot|screenshot|截图|屏幕快照|snipaste|snip_)"),
                    new String[]{"图片", "截图"}, "AI建议：疑似屏幕截图", 0.8),
            new NameRule(Pattern.compile("(?i)(微信图片|wechat|qq图片|mmexport|mm_)"),
                    new String[]{"图片", "聊天记录"}, "AI建议：疑似聊天工具导出", 0.75),
            new NameRule(Pattern.compile("(?i)^(img[_-]?\\d|dsc[_-]?\\d|pxl[_-]?\\d|dji[_-]?\\d|照片|photo)"),
                    new String[]{"图片", "照片"}, "AI建议：疑似相机/手机照片", 0.7),
            new NameRule(Pattern.compile("(?i)(发票|invoice|账单|receipt|收据|对账单)"),
                    new String[]{"文档", "财务"}, "AI建议：疑似财务票据", 0.7),
            new NameRule(Pattern.compile("(?i)(简历|resume|\\bcv\\b|个人简历)"),
                    new String[]{"文档", "简历"}, "AI建议：疑似简历", 0.7),
            new NameRule(Pattern.compile("(?i)(合同|contract|协议|agreement)"),
                    new String[]{"文档", "合同"}, "AI建议：疑似合同/协议", 0.7),
            new NameRule(Pattern.compile("(?i)(报告|report|周报|月报|日报|总结|summary)"),
                    new String[]{"文档", "报告"}, "AI建议：疑似报告/总结", 0.65)
    );

    /** Names that carry no information — flag for renaming (but the move itself is safe). */
    private static final Pattern MEANINGLESS = Pattern.compile(
            "(?i)(新建|未命名|untitled|无标题|new (document|text|microsoft)|文档\\s*\\(?\\d|下载|download)");

    /** Tokens stripped when normalizing names for similarity clustering. */
    private static final Pattern VERSION_MARKERS = Pattern.compile(
            "(?i)(copy|副本|final|最终版|最终|定稿|draft|草稿|new|version|(?<![a-z0-9])v\\d+|修改|revised)");

    @Override
    public List<FileAction> suggest(List<FileMeta> files, OrganizeContext context) {
        List<FileAction> out = new ArrayList<>();

        Map<String, List<FileMeta>> clusters = buildSimilarityClusters(files);
        Map<Path, String> similarityNotes = similarityNotes(clusters);

        for (FileMeta f : files) {
            // "By project": if this file is part of a name cluster, propose a project folder.
            if (context.scheme() == OrganizeContext.Scheme.BY_PROJECT) {
                String key = clusterKeyOf(f, clusters);
                if (key != null) {
                    Path target = resolve(context.rootDir(), "项目_" + shorten(key), f.fileName());
                    out.add(FileAction.ai(f.path(), target, ActionType.MOVE,
                            "AI建议（按项目）：与同组文件疑似同一主题「" + shorten(key) + "」", 0.6));
                    continue;
                }
            }

            String note = similarityNotes.get(f.path());
            NameHint hint = nameHint(f);

            if (hint != null) {
                Path target = resolve(context.rootDir(), hint.folder, f.fileName());
                String reason = hint.reason + (note != null ? "  |  " + note : "");
                out.add(FileAction.ai(f.path(), target, ActionType.MOVE, reason, hint.confidence));
            } else if (note != null) {
                // No re-classification opinion, but the similarity is worth surfacing.
                out.add(FileAction.skip(f.path(), "AI观察：" + note, 0.5, FileAction.Source.AI));
            } else if (MEANINGLESS.matcher(f.baseName()).find()) {
                // Extension is trustworthy, so we don't move it elsewhere; we just advise a rename.
                out.add(FileAction.skip(f.path(),
                        "AI观察：文件名缺乏信息，建议整理后重命名", 0.5, FileAction.Source.AI));
            }
        }
        return out;
    }

    // ---- content-aware name/hint resolution -------------------------------------------

    private record NameHint(String[] folder, String reason, double confidence) {
    }

    private NameHint nameHint(FileMeta f) {
        // 1. Strong signals from the file name.
        for (NameRule rule : NAME_RULES) {
            if (rule.pattern.matcher(f.fileName()).find()) {
                return new NameHint(rule.folder, rule.reason, rule.confidence);
            }
        }
        // 2. Content sniff for text-ish / extension-less files whose extension may mislead.
        if (shouldSniff(f)) {
            String sniff = sniff(f);
            if (sniff != null) {
                ContentKind kind = classifyContent(sniff);
                if (kind != null) {
                    return new NameHint(kind.folder, kind.reason, kind.confidence);
                }
            }
        }
        return null;
    }

    private boolean shouldSniff(FileMeta f) {
        if (f.size() == 0 || f.size() > MAX_SNIFF_SIZE) {
            return false;
        }
        String e = f.extension();
        return e.isEmpty() || e.equals("txt") || e.equals("dat") || e.equals("log") || e.equals("bin");
    }

    private String sniff(FileMeta f) {
        try (var in = Files.newInputStream(f.path())) {
            byte[] buf = in.readNBytes(SNIFF_BYTES);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private enum ContentKind {
        CSV(new String[]{"表格"}, "AI建议：内容疑似表格数据(CSV)", 0.65),
        CODE(new String[]{"代码"}, "AI建议：内容疑似源代码/脚本", 0.6),
        JSON(new String[]{"代码"}, "AI建议：内容疑似 JSON 数据", 0.6);

        final String[] folder;
        final String reason;
        final double confidence;

        ContentKind(String[] folder, String reason, double confidence) {
            this.folder = folder;
            this.reason = reason;
            this.confidence = confidence;
        }
    }

    private ContentKind classifyContent(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[') {
            return ContentKind.JSON;
        }
        if (Pattern.compile("(?m)^\\s*(import |package |def |function |class |public |#include|<\\?php)")
                .matcher(text).find()) {
            return ContentKind.CODE;
        }
        // CSV-ish: several lines that each contain the same delimiter a few times.
        String[] lines = text.split("\\R", 6);
        if (lines.length >= 3) {
            long commaLines = 0;
            for (String line : lines) {
                if (countChar(line, ',') >= 2 || countChar(line, '\t') >= 2) {
                    commaLines++;
                }
            }
            if (commaLines >= 3) {
                return ContentKind.CSV;
            }
        }
        return null;
    }

    // ---- similarity clustering --------------------------------------------------------

    private Map<String, List<FileMeta>> buildSimilarityClusters(List<FileMeta> files) {
        Map<String, List<FileMeta>> byStem = new LinkedHashMap<>();
        for (FileMeta f : files) {
            String stem = normalizeStem(f.baseName());
            if (stem.length() < 2) {
                continue; // too generic to cluster on
            }
            byStem.computeIfAbsent(stem, k -> new ArrayList<>()).add(f);
        }
        byStem.values().removeIf(list -> list.size() < 2);
        return byStem;
    }

    private Map<Path, String> similarityNotes(Map<String, List<FileMeta>> clusters) {
        Map<Path, String> notes = new LinkedHashMap<>();
        for (List<FileMeta> group : clusters.values()) {
            String note = "疑似同组文件（共 " + group.size() + " 个），建议人工确认后归档/合并";
            for (FileMeta f : group) {
                notes.put(f.path(), note);
            }
        }
        return notes;
    }

    private String clusterKeyOf(FileMeta f, Map<String, List<FileMeta>> clusters) {
        String stem = normalizeStem(f.baseName());
        return clusters.containsKey(stem) ? stem : null;
    }

    /** Collapses versioning/copy noise so {@code 报告v1} and {@code 报告最终版} share a stem. */
    String normalizeStem(String base) {
        String s = base.toLowerCase();
        s = s.replaceAll("\\(\\s*\\d+\\s*\\)", " ");   // (1) (2)
        s = s.replaceAll("[\\-_]+", " ");
        s = VERSION_MARKERS.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\d+", " ");                  // remaining digits
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    // ---- small helpers ----------------------------------------------------------------

    private static Path resolve(Path root, String[] folder, String fileName) {
        Path p = root;
        for (String seg : folder) {
            p = p.resolve(seg);
        }
        return p.resolve(fileName);
    }

    private static Path resolve(Path root, String folder, String fileName) {
        return root.resolve(folder).resolve(fileName);
    }

    private static long countChar(String s, char c) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String shorten(String key) {
        String k = key.trim();
        return k.length() <= 20 ? k : k.substring(0, 20);
    }

    @Override
    public String name() {
        return "本地启发式AI";
    }
}
