package com.filenest.core.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Fetches model names from OpenAI-compatible /models and Ollama /api/tags endpoints. */
public final class UrlModelCatalog {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;

    public UrlModelCatalog() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    public List<String> fetch(String apiEndpoint, String apiKey) throws Exception {
        URI endpoint = validate(apiEndpoint);
        List<URI> candidates = candidateUris(endpoint);
        Exception last = null;
        for (URI uri : candidates) {
            try {
                List<String> models = request(uri, apiKey == null ? "" : apiKey.trim());
                if (!models.isEmpty()) return models;
                last = new IllegalStateException("响应中没有模型");
            } catch (Exception ex) {
                last = ex;
            }
        }
        throw new IllegalStateException("获取模型失败: " + (last == null ? "未知错误" : last.getMessage()), last);
    }

    private List<String> request(URI uri, String apiKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json").GET();
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + "（" + uri + "）");
        }
        JsonNode root = JSON.readTree(response.body());
        Set<String> names = new LinkedHashSet<>();
        JsonNode data = root.path("data");
        if (data.isArray()) for (JsonNode item : data) add(names, item, "id", "name", "model");
        JsonNode models = root.path("models");
        if (models.isArray()) for (JsonNode item : models) add(names, item, "name", "model", "id");
        if (root.isArray()) for (JsonNode item : root) add(names, item, "id", "name", "model");
        List<String> result = new ArrayList<>(names);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private void add(Set<String> target, JsonNode item, String... fields) {
        if (item.isTextual() && !item.asText().isBlank()) {
            target.add(item.asText().trim());
            return;
        }
        for (String field : fields) {
            JsonNode value = item.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                target.add(value.asText().trim());
                return;
            }
        }
    }

    private List<URI> candidateUris(URI endpoint) throws Exception {
        URI first = ApiEndpointResolver.models(endpoint.toString());
        URI ollama = new URI(endpoint.getScheme(), endpoint.getAuthority(), "/api/tags", null, null);
        return first.equals(ollama) ? List.of(first) : List.of(first, ollama);
    }

    private URI validate(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("请先填写 AI API 地址");
        URI uri = URI.create(endpoint.trim());
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("AI API 地址必须是有效的 http/https URL");
        }
        return uri;
    }
}
