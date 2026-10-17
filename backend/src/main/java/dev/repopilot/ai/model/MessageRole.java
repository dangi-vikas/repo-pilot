package dev.repopilot.ai.model;

/** Who authored a message in a model conversation. */
public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT;

    /** Lowercase wire form used by Ollama and OpenAI-compatible APIs. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
