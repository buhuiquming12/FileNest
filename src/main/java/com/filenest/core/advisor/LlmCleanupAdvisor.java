package com.filenest.core.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.filenest.core.storage.CleanupSuggestion;
import com.filenest.core.storage.FolderUsage;
import com.filenest.core.storage.LargeFileUsage;
import com.filenest.core.storage.StorageScanResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** URL-model cleanup advisor. It returns recommendations only and cannot delete files. */
public final class LlmCleanupAdvisor implements CleanupAdvisor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);
    private static final int MAX_TOP_LEVEL_FOLDERS = 30;
    private static final int MAX_FOLDER_SAMPLES = 45;
    private static final int MAX_LARGE_FILES = 45;
    private static final int MAX_REMOTE_SUGGESTIONS = 30;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient client;

    public LlmCleanupAdvisor(String endpoint, String apiKey, String model) {
        this.endpoint = ApiEndpointResolver.chatCompletions(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean available() { return !endpoint.isBlank(); }

    @Override
    public List<CleanupSuggestion> suggest(StorageScanResult scan) {
        if (!available()) return List.of();
        try {
            String response = call(buildPrompt(scan));
            return parse(response, scan);
        } catch (HttpTimeoutException timeout) {
            throw new IllegalStateException("AI 清理建议调用失败：模型在 "
                    + REQUEST_TIMEOUT.toSeconds() + " 秒内未返回；请换用响应更快的模型", timeout);
        } catch (Exception ex) {
            throw new IllegalStateException("AI 清理建议调用失败: " + ex.getMessage(), ex);
        }
    }

    String buildPrompt(StorageScanResult scan) {
        StringBuilder out = new StringBuilder("你是谨慎、全面且可操作的磁盘清理顾问，只能建议，绝不能执行删除。\n")
                .append("只返回JSON：{\"suggestions\":[{\"path\":\"扫描清单中的相对路径\",\"decision\":\"DELETE|REVIEW|KEEP\",\"confidence\":0.0,\"reason\":\"具体操作、收益和风险\"}]}。\n")
                .append("覆盖缓存/临时文件、开发构建产物、旧安装包和压缩包、日志/转储、大文件迁移、系统文件保护等类别。")
                .append("只有明确可重建的缓存或临时文件才用DELETE；用户文档、照片、源码、系统和应用目录必须KEEP或REVIEW。")
                .append("禁止编造路径；同一路径只返回一次；按预计释放空间排序，最多返回")
                .append(MAX_REMOTE_SUGGESTIONS).append("条高价值建议。\n")
                .append("扫描根目录：").append(scan.root()).append("\n")
                .append("统计：").append(scan.total().fileCount()).append(" files, ")
                .append(scan.total().folderCount()).append(" folders, ")
                .append(scan.total().inaccessibleCount()).append(" inaccessible\n")
                .append("一级目录：\n");
        scan.folders().stream().limit(MAX_TOP_LEVEL_FOLDERS).forEach(folder -> out.append("DIR ")
                .append(relative(scan.root(), folder.path())).append(" | ").append(folder.bytes())
                .append(" bytes | ").append(folder.fileCount()).append(" files\n"));
        out.append("重点嵌套目录（大目录以及缓存/构建目录）：\n");
        scan.folderSamples().stream().limit(MAX_FOLDER_SAMPLES).forEach(folder -> out.append("DIR ")
                .append(relative(scan.root(), folder.path())).append(" | ").append(folder.bytes())
                .append(" bytes | ").append(folder.fileCount()).append(" files\n"));
        out.append("大文件：\n");
        scan.largestFiles().stream().limit(MAX_LARGE_FILES).forEach(file -> out.append("FILE ")
                .append(relative(scan.root(), file.path())).append(" | ").append(file.bytes())
                .append(" bytes | modified ").append(file.lastModified()).append('\n'));
        return out.toString();
    }

    private String call(String prompt) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", "Return valid JSON only. Never perform operations.");
        messages.addObject().put("role", "user").put("content", prompt);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT).header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response = LlmHttpRetry.send(client, request.build());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String snippet = response.body() == null ? "" : response.body().trim();
            if (snippet.length() > 300) snippet = snippet.substring(0, 300) + "...";
            throw new IllegalStateException("HTTP " + response.statusCode()
                    + (snippet.isBlank() ? "" : ": " + snippet));
        }
        return response.body();
    }

    private List<CleanupSuggestion> parse(String response, StorageScanResult scan) throws Exception {
        JsonNode envelope = JSON.readTree(response);
        JsonNode payload = envelope;
        if (!envelope.has("suggestions") && !envelope.isArray()) {
            JsonNode content = envelope.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) content = envelope.path("message").path("content");
            if (!content.isTextual()) content = envelope.path("response");
            if (!content.isTextual()) throw new IllegalArgumentException("无法识别 AI 响应格式");
            payload = JSON.readTree(LlmJsonText.extract(content.asText()));
        }
        JsonNode suggestions = payload.isArray() ? payload : payload.path("suggestions");
        if (!suggestions.isArray()) throw new IllegalArgumentException("AI 返回内容中没有 suggestions 数组");

        Map<Path, Long> inventory = new HashMap<>();
        for (FolderUsage folder : scan.folders()) inventory.put(folder.path().toAbsolutePath().normalize(), folder.bytes());
        for (FolderUsage folder : scan.folderSamples()) inventory.put(folder.path().toAbsolutePath().normalize(), folder.bytes());
        for (LargeFileUsage file : scan.largestFiles()) inventory.put(file.path().toAbsolutePath().normalize(), file.bytes());
        List<CleanupSuggestion> result = new ArrayList<>();
        for (JsonNode item : suggestions) {
            String value = text(item, "path", "file", "folder");
            if (value == null) continue;
            Path candidate;
            try { candidate = scan.root().resolve(Path.of(value)).toAbsolutePath().normalize(); }
            catch (RuntimeException invalid) { continue; }
            Long bytes = inventory.get(candidate);
            if (bytes == null || !candidate.startsWith(scan.root())) continue;
            CleanupSuggestion.Decision decision = decision(text(item, "decision", "action", "recommendation"));
            String reason = text(item, "reason", "why", "explanation");
            if (reason == null) reason = "AI 建议人工确认";
            result.add(new CleanupSuggestion(candidate, bytes, decision, reason,
                    item.path("confidence").asDouble(0.5), "URL AI（" + model + "）"));
        }
        return result;
    }

    private CleanupSuggestion.Decision decision(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("DELETE") || normalized.contains("可删除")) return CleanupSuggestion.Decision.DELETE;
        if (normalized.equals("KEEP") || normalized.contains("保留")) return CleanupSuggestion.Decision.KEEP;
        return CleanupSuggestion.Decision.REVIEW;
    }

    private static String relative(Path root, Path path) {
        try {
            Path relative = root.relativize(path);
            return relative.getNameCount() == 0 ? "." : relative.toString();
        } catch (IllegalArgumentException ex) { return path.toString(); }
    }


    private String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) return value.asText();
        }
        return null;
    }


    @Override
    public String name() { return "URL AI（" + model + "）"; }
}
