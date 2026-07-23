package com.filenest.core.advisor;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlModelCatalogTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void derivesModelsUrlAndParsesOpenAiCatalog() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        String endpoint = "http://localhost:" + server.getAddress().getPort();
        List<String> models = new UrlModelCatalog().fetch(endpoint, "secret");

        assertEquals(List.of("model-a", "model-b"), models);
        assertEquals("Bearer secret", authorization.get());
    }

    @Test
    void reportsTheOriginalAuthenticationErrorAlongsideFallbackFailure() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, 401, "invalid api key"));
        server.createContext("/api/tags", exchange -> respond(exchange, 404, "not ollama"));
        server.start();

        String endpoint = "http://localhost:" + server.getAddress().getPort();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new UrlModelCatalog().fetch(endpoint, "bad-key"));

        assertTrue(error.getMessage().contains("HTTP 401: invalid api key"), error.getMessage());
        assertTrue(error.getMessage().contains("HTTP 404: not ollama"), error.getMessage());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
