package com.filenest.core.advisor;

import com.filenest.model.FileAction;
import com.filenest.model.FileMeta;
import com.filenest.model.OrganizeContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmAiAdvisorTest {
    @TempDir Path temp;

    @Test
    void callsOpenAiCompatibleUrlAndParsesSafeSuggestion() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = "{\\\"suggestions\\\":[{\\\"file\\\":\\\"notes.txt\\\","
                    + "\\\"folder\\\":\\\"Project A\\\",\\\"confidence\\\":0.82,"
                    + "\\\"reason\\\":\\\"belongs together\\\"}]}";
            String body = "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Path source = Files.writeString(temp.resolve("notes.txt"), "hello");
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            LlmAiAdvisor advisor = new LlmAiAdvisor(endpoint, "secret", "test-model");

            List<FileAction> actions = advisor.suggest(List.of(FileMeta.of(source)),
                    new OrganizeContext(temp, OrganizeContext.Scheme.BY_PROJECT));

            assertEquals(1, actions.size());
            assertEquals(FileAction.Source.AI, actions.get(0).source());
            assertTrue(actions.get(0).requiresConfirm());
            assertEquals(temp.resolve("Project A/notes.txt").toAbsolutePath().normalize(),
                    actions.get(0).targetPath());
            assertEquals("Bearer secret", authorization.get());
            assertTrue(!requestBody.get().contains("temperature"),
                    "temperature must be omitted for reasoning-model compatibility");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransientServiceUnavailableResponses() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int attempt = requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String body;
            int status;
            if (attempt < 3) {
                status = 503;
                body = "{\"error\":{\"message\":\"worker capacity exhausted\"}}";
                exchange.getResponseHeaders().add("Retry-After", "0");
            } else {
                status = 200;
                body = "{\"suggestions\":[{\"file\":\"retry.txt\"," +
                        "\"folder\":\"Recovered\",\"confidence\":0.9}]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Path source = Files.writeString(temp.resolve("retry.txt"), "hello");
            LlmAiAdvisor advisor = new LlmAiAdvisor(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", "test-model");

            List<FileAction> actions = advisor.suggest(List.of(FileMeta.of(source)),
                    OrganizeContext.byType(temp));

            assertEquals(3, requests.get());
            assertEquals(1, actions.size());
            assertEquals(temp.resolve("Recovered/retry.txt").toAbsolutePath().normalize(),
                    actions.get(0).targetPath());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonHttpEndpoint() {
        boolean rejected = false;
        try {
            new LlmAiAdvisor("file:///tmp/model", "", "model");
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        assertTrue(rejected);
    }

    @Test
    void splitsLargeInventoriesIntoBoundedRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"suggestions\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            List<FileMeta> files = new ArrayList<>();
            for (int i = 0; i < 61; i++) {
                String name = "file-" + i + ".txt";
                files.add(new FileMeta(temp.resolve(name), name, "txt", 10, Instant.EPOCH,
                        false, false));
            }
            LlmAiAdvisor advisor = new LlmAiAdvisor(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", "test-model");

            assertTrue(advisor.suggest(files, OrganizeContext.byType(temp)).isEmpty());
            assertEquals(2, requests.get(), "61 files should be sent as two bounded requests");
        } finally {
            server.stop(0);
        }
    }
}
