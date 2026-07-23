package com.filenest.core.advisor;

/** Extracts the JSON value from common LLM text wrappers. */
final class LlmJsonText {
    private LlmJsonText() { }

    static String extract(String text) {
        if (text == null) return "";
        String value = text.strip();
        if (value.startsWith("\uFEFF")) value = value.substring(1).stripLeading();

        int arrayStart = value.indexOf('[');
        int arrayEnd = value.lastIndexOf(']');
        if (arrayStart == 0 && arrayEnd > arrayStart) {
            return value.substring(arrayStart, arrayEnd + 1);
        }
        int objectStart = value.indexOf('{');
        int objectEnd = value.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return value.substring(objectStart, objectEnd + 1);
        }
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return value.substring(arrayStart, arrayEnd + 1);
        }
        return value;
    }
}
