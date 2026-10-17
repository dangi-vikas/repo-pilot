package dev.repopilot.ai.model;

import java.util.List;

/**
 * A provider-agnostic completion request.
 *
 * @param model         provider model identifier, resolved from configuration
 * @param messages      ordered conversation, including the system prompt
 * @param temperature   sampling temperature; low by design because RepoPilot asks for
 *                      grounded analysis, not creative writing
 * @param maxTokens     upper bound on generated tokens
 * @param jsonMode      when true the provider is asked to emit a single JSON object
 * @param requestId     correlation id, forwarded for provider-side tracing
 */
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        int maxTokens,
        boolean jsonMode,
        String requestId
) {

    public ChatRequest {
        messages = List.copyOf(messages);
    }

    public static Builder builder(String model) {
        return new Builder(model);
    }

    /** Total characters of input, used for context-budget accounting. */
    public int inputCharacters() {
        return messages.stream().mapToInt(ChatMessage::characterCount).sum();
    }

    public static final class Builder {
        private final String model;
        private final java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        private double temperature = 0.1;
        private int maxTokens = 2048;
        private boolean jsonMode;
        private String requestId;

        private Builder(String model) {
            this.model = model;
        }

        public Builder system(String content) {
            if (content != null && !content.isBlank()) {
                messages.add(ChatMessage.system(content));
            }
            return this;
        }

        public Builder user(String content) {
            messages.add(ChatMessage.user(content));
            return this;
        }

        public Builder assistant(String content) {
            messages.add(ChatMessage.assistant(content));
            return this;
        }

        public Builder message(ChatMessage message) {
            messages.add(message);
            return this;
        }

        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder jsonMode(boolean jsonMode) {
            this.jsonMode = jsonMode;
            return this;
        }

        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public ChatRequest build() {
            if (messages.isEmpty()) {
                throw new IllegalStateException("A chat request requires at least one message");
            }
            return new ChatRequest(model, messages, temperature, maxTokens, jsonMode, requestId);
        }
    }
}
