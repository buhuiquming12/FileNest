package com.filenest.core.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.filenest.model.ActionType;
import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Calls an OpenAI-compatible chat-completions URL and converts its structured JSON answer
 * into safe, confirm-required file suggestions. The API URL is supplied by the user; an
 * API key is optional so local services such as Ollama-compatible gateways also work.
 */
public final class LlmAiAdvisor implements AiAdvisor {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient client;

    public LlmAiAdvisor() {
        this(System.getenv("FILENEST_LLM_ENDPOINT"),
                System.getenv("FILENEST_LLM_API_KEY"),
                System.getenv("FILENEST_LLM_MODEL"));
    }

    public LlmAiAdvisor(String endpoint, String apiKey) {
        this(endpoint, apiKey, null);
    }

    public LlmAiAdvisor(String endpoint, String apiKey, String model) {
        this.endpoint = ApiEndpointResolver.chatCompletions(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean available() {
        return !endpoint.isBlank();
    }

    @Override
    public List<FileAction> suggest(List<FileMeta> files, OrganizeContext context) {
        if (!available() || files.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            String response = callModel(buildPrompt(files, context));
            return parseResponse(response, files, context);
        } catch (Exception e) {
            // Configured instances are timeout/fallback wrapped by OrganizeService.
            throw new IllegalStateException("AI API 调用失败: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(List<FileMeta> files, OrganizeContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是文件整理助手，只给出建议，不能执行操作。整理方式：")
                .append(context.scheme().label()).append("。\n")
                .append("必须只返回 JSON，不要 Markdown。格式：")
                .append("{\"suggestions\":[{\"file\":\"原文件名\",\"folder\":\"目标子文件夹\",")
                .append("\"confidence\":0.0,\"reason\":\"简短理由\"}]}。\n")
                .append("folder 必须是相对目标文件夹名，不能包含盘符、.. 或原文件名；")
                .append("confidence 必须在 0 到 1 之间。文件如下：\n");
        for (FileMeta file : files) {
            sb.append("- ").append(file.fileName())
                    .append(" (").append(file.size()).append(" bytes)\n");
        }
        return sb.toString();
    }

    private String callModel(String prompt) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system")
                .put("content", "Return valid JSON only. Never propose deleting files.");
        messages.addObject().put("role", "user").put("content", prompt);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String snippet = response.body() == null ? "" : response.body();
            if (snippet.length() > 300) {
                snippet = snippet.substring(0, 300) + "…";
            }
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + snippet);
        }
        return response.body();
    }

    private List<FileAction> parseResponse(String response, List<FileMeta> files,
                                            OrganizeContext context) throws Exception {
        JsonNode envelope = JSON.readTree(response);
        JsonNode payload = extractPayload(envelope);
        JsonNode suggestions = payload.isArray() ? payload : payload.path("suggestions");
        if (!suggestions.isArray()) {
            throw new IllegalArgumentException("AI 返回内容中没有 suggestions 数组");
        }

        Map<String, FileMeta> byName = new HashMap<>();
        for (FileMeta file : files) {
            byName.put(file.fileName(), file);
            byName.putIfAbsent(file.fileName().toLowerCase(Locale.ROOT), file);
        }

        List<FileAction> actions = new ArrayList<>();
        for (JsonNode item : suggestions) {
            String fileName = firstText(item, "file", "fileName", "filename", "source");
            String folder = firstText(item, "folder", "category", "targetFolder", "target");
            FileMeta file = byName.get(fileName);
            if (file == null && fileName != null) {
                file = byName.get(fileName.toLowerCase(Locale.ROOT));
            }
            if (file == null || folder == null || folder.isBlank()) {
                continue;
            }

            Path root = context.rootDir().toAbsolutePath().normalize();
            Path relativeFolder;
            try {
                relativeFolder = Path.of(folder.trim()).normalize();
            } catch (RuntimeException invalidPath) {
                continue;
            }
            if (relativeFolder.isAbsolute() || relativeFolder.getNameCount() == 0
                    || relativeFolder.startsWith("..") || relativeFolder.toString().equals(".")) {
                continue;
            }
            Path target = root.resolve(relativeFolder).resolve(file.fileName()).normalize();
            if (!target.startsWith(root) || target.equals(file.path().toAbsolutePath().normalize())) {
                continue;
            }

            double confidence = item.path("confidence").asDouble(0.5);
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            String reason = firstText(item, "reason", "why", "explanation");
            if (reason == null || reason.isBlank()) {
                reason = "AI API 建议归入「" + folder.trim() + "」";
            } else {
                reason = "AI API：" + reason.trim();
            }
            actions.add(FileAction.ai(file.path(), target, ActionType.MOVE, reason, confidence));
        }
        return actions;
    }

    /** Supports OpenAI, Ollama-style and direct JSON response envelopes. */
    private JsonNode extractPayload(JsonNode envelope) throws Exception {
        if (envelope.has("suggestions") || envelope.isArray()) {
            return envelope;
        }
        JsonNode content = envelope.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            content = envelope.path("message").path("content");
        }
        if (!content.isTextual()) {
            content = envelope.path("response");
        }
        if (!content.isTextual()) {
            throw new IllegalArgumentException("无法识别 AI API 响应格式");
        }
        String text = stripCodeFence(content.asText().trim());
        return JSON.readTree(text);
    }

    private String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLine = text.indexOf('\n');
        int end = text.lastIndexOf("```");
        return firstLine >= 0 && end > firstLine ? text.substring(firstLine + 1, end).trim() : text;
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }


    @Override
    public String name() {
        return available() ? "URL 大模型 API（" + model + "）" : "大模型 AI（未配置）";
    }
}
