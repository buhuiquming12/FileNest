package com.filenest.core.advisor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiEndpointResolverTest {
    @Test
    void appendsV1OperationToDomainOnly() {
        assertEquals("https://api.example.com/v1/chat/completions",
                ApiEndpointResolver.chatCompletions("https://api.example.com"));
        assertEquals("https://api.example.com/v1/models",
                ApiEndpointResolver.models("https://api.example.com").toString());
    }

    @Test
    void acceptsV1BaseAndPreservesCompleteEndpoint() {
        assertEquals("https://api.example.com/v1/chat/completions",
                ApiEndpointResolver.chatCompletions("https://api.example.com/v1/"));
        assertEquals("https://api.example.com/v1/chat/completions",
                ApiEndpointResolver.chatCompletions("https://api.example.com/v1/chat/completions"));
    }

    @Test
    void appendsV1AfterCustomGatewayBasePath() {
        assertEquals("https://api.example.com/gateway/v1/chat/completions",
                ApiEndpointResolver.chatCompletions("https://api.example.com/gateway/"));
        assertEquals("https://api.example.com/gateway/v1/models",
                ApiEndpointResolver.models("https://api.example.com/gateway/").toString());
    }

    @Test
    void rejectsNonHttpAddress() {
        assertThrows(IllegalArgumentException.class,
                () -> ApiEndpointResolver.chatCompletions("file:///tmp/model"));
    }

    @Test
    void normalizesOperationsWhoseRequestSchemaIsNotChatCompatible() {
        assertEquals("https://api.example.com/v1/chat/completions",
                ApiEndpointResolver.chatCompletions("https://api.example.com/v1/responses"));
        assertEquals("http://localhost:11434/api/chat",
                ApiEndpointResolver.chatCompletions("http://localhost:11434/api/generate"));
    }
}
