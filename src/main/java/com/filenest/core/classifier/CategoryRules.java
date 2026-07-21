package com.filenest.core.classifier;

import java.util.Map;

/**
 * Static, deterministic mapping from a file extension to a category folder name.
 *
 * <p>This is intentionally boring data, not AI: extension-based bucketing is fast,
 * offline and 100% reproducible, so it forms the always-available base layer of the plan.
 * Extend the map to teach the tool new extensions.
 */
public final class CategoryRules {

    /** Category used when an extension is unknown. */
    public static final String OTHERS = "其他";

    private static final Map<String, String> EXTENSION_TO_CATEGORY = buildMap();

    private CategoryRules() {
    }

    /**
     * @param extension lower-case extension without the dot ("" if none)
     * @return the category folder name; {@link #OTHERS} when unrecognized
     */
    public static String categoryFor(String extension) {
        if (extension == null || extension.isBlank()) {
            return OTHERS;
        }
        return EXTENSION_TO_CATEGORY.getOrDefault(extension.toLowerCase(), OTHERS);
    }

    /** True when the extension is one we recognize (vs. falling through to {@link #OTHERS}). */
    public static boolean isKnown(String extension) {
        return extension != null && EXTENSION_TO_CATEGORY.containsKey(extension.toLowerCase());
    }

    private static Map<String, String> buildMap() {
        Map<String, String> m = new java.util.HashMap<>();

        String documents = "文档";
        for (String e : new String[]{"doc", "docx", "pdf", "txt", "rtf", "odt", "md", "pages", "wps"}) {
            m.put(e, documents);
        }
        String spreadsheets = "表格";
        for (String e : new String[]{"xls", "xlsx", "csv", "ods", "numbers", "et"}) {
            m.put(e, spreadsheets);
        }
        String slides = "演示";
        for (String e : new String[]{"ppt", "pptx", "odp", "key", "dps"}) {
            m.put(e, slides);
        }
        String images = "图片";
        for (String e : new String[]{"jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic", "tiff", "ico", "raw"}) {
            m.put(e, images);
        }
        String audio = "音频";
        for (String e : new String[]{"mp3", "wav", "flac", "aac", "ogg", "m4a", "wma"}) {
            m.put(e, audio);
        }
        String video = "视频";
        for (String e : new String[]{"mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg"}) {
            m.put(e, video);
        }
        String archives = "压缩包";
        for (String e : new String[]{"zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"}) {
            m.put(e, archives);
        }
        String programs = "程序";
        for (String e : new String[]{"exe", "msi", "apk", "dmg", "deb", "rpm", "bat", "sh", "app"}) {
            m.put(e, programs);
        }
        String code = "代码";
        for (String e : new String[]{"java", "py", "js", "ts", "c", "cpp", "h", "cs", "go", "rs",
                "rb", "php", "html", "css", "json", "xml", "yml", "yaml", "sql", "kt"}) {
            m.put(e, code);
        }
        String ebooks = "电子书";
        for (String e : new String[]{"epub", "mobi", "azw3", "azw", "djvu"}) {
            m.put(e, ebooks);
        }
        String fonts = "字体";
        for (String e : new String[]{"ttf", "otf", "woff", "woff2"}) {
            m.put(e, fonts);
        }
        return Map.copyOf(m);
    }
}
