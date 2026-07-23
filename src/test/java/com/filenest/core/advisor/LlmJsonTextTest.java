package com.filenest.core.advisor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmJsonTextTest {
    @Test
    void extractsJsonAfterReasoningOrMarkdownText() {
        assertEquals("{\"suggestions\":[]}",
                LlmJsonText.extract("Thinking first...\n```json\n{\"suggestions\":[]}\n```"));
    }

    @Test
    void preservesDirectJsonArray() {
        assertEquals("[{\"file\":\"a.txt\"}]",
                LlmJsonText.extract("[{\"file\":\"a.txt\"}]"));
    }
}
