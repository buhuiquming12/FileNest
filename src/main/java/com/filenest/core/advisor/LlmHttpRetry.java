package com.filenest.core.advisor;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** Retries transient model-capacity and rate-limit responses with bounded backoff. */
final class LlmHttpRetry {
    private static final int MAX_ATTEMPTS = 3;

    private LlmHttpRetry() { }

    static HttpResponse<String> send(HttpClient client, HttpRequest request) throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!transientStatus(response.statusCode()) || attempt == MAX_ATTEMPTS) {
                return response;
            }
            long delayMillis = retryDelayMillis(response, attempt);
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
        return response;
    }

    private static boolean transientStatus(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static long retryDelayMillis(HttpResponse<?> response, int attempt) {
        String retryAfter = response.headers().firstValue("Retry-After").orElse("").trim();
        if (!retryAfter.isEmpty()) {
            try {
                long seconds = Long.parseLong(retryAfter);
                return Math.max(0, Math.min(10, seconds)) * 1000;
            } catch (NumberFormatException ignored) {
                // HTTP-date Retry-After is uncommon for model gateways; use bounded backoff.
            }
        }
        return attempt == 1 ? 1000 : 3000;
    }
}
