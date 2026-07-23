package com.filenest.core.advisor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Normalizes user-friendly API base URLs into OpenAI-compatible operation endpoints. */
public final class ApiEndpointResolver {
    private static final String CHAT_COMPLETIONS = "/chat/completions";
    private static final String RESPONSES = "/responses";
    private static final String MODELS = "/models";

    private ApiEndpointResolver() { }

    /**
     * Accepts a domain, a /v1 base URL, or a complete operation URL.
     * Domains and custom base paths receive /v1/chat/completions automatically.
     */
    public static String chatCompletions(String input) {
        if (input == null || input.isBlank()) return "";
        URI endpoint = validate(input);
        String path = cleanPath(endpoint);
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(CHAT_COMPLETIONS) || lower.endsWith("/api/chat")) {
            return withPath(endpoint, path).toString();
        }
        // This client sends a chat/messages request body. Normalize operation URLs that
        // use a different request schema instead of preserving them and sending invalid JSON.
        if (lower.endsWith(RESPONSES)) {
            path = path.substring(0, path.length() - RESPONSES.length()) + CHAT_COMPLETIONS;
        } else if (lower.endsWith("/api/generate")) {
            path = path.substring(0, path.length() - "/api/generate".length()) + "/api/chat";
        } else if (lower.endsWith(MODELS)) {
            path = path.substring(0, path.length() - MODELS.length()) + CHAT_COMPLETIONS;
        } else if (lower.endsWith("/v1")) {
            path += CHAT_COMPLETIONS;
        } else {
            path += "/v1" + CHAT_COMPLETIONS;
        }
        return withPath(endpoint, path).toString();
    }

    /** Derives the matching OpenAI /models endpoint from any accepted API URL. */
    public static URI models(String input) {
        URI endpoint = validate(input);
        String path = cleanPath(endpoint);
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/api/chat") || lower.endsWith("/api/generate")
                || lower.endsWith("/api/tags")) {
            int marker = lower.lastIndexOf("/api/");
            return withPath(endpoint, path.substring(0, marker) + "/api/tags");
        }
        if (lower.endsWith(MODELS)) return withPath(endpoint, path);
        URI chat = URI.create(chatCompletions(input));
        String chatPath = cleanPath(chat);
        String modelPath;
        if (chatPath.toLowerCase(Locale.ROOT).endsWith(CHAT_COMPLETIONS)) {
            modelPath = chatPath.substring(0, chatPath.length() - CHAT_COMPLETIONS.length()) + MODELS;
        } else if (chatPath.toLowerCase(Locale.ROOT).endsWith(RESPONSES)) {
            modelPath = chatPath.substring(0, chatPath.length() - RESPONSES.length()) + MODELS;
        } else {
            modelPath = chatPath + MODELS;
        }
        return withPath(chat, modelPath);
    }

    private static URI validate(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("请先填写 AI API 域名");
        URI uri;
        try {
            uri = URI.create(input.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("AI API 地址格式无效", invalid);
        }
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("AI API 地址必须是有效的 http/https 域名");
        }
        return uri;
    }

    private static String cleanPath(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        path = path.replaceAll("/+$", "");
        return path.equals("/") ? "" : path;
    }

    private static URI withPath(URI endpoint, String path) {
        try {
            return new URI(endpoint.getScheme(), endpoint.getAuthority(), path, null, null);
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("无法生成 AI API 地址", impossible);
        }
    }
}
