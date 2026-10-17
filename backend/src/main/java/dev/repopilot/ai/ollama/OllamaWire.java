package dev.repopilot.ai.ollama;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Wire types for the Ollama HTTP API.
 *
 * <p>Kept as package-private records next to the provider: the wire format is an
 * implementation detail of this adapter and must not leak into the rest of RepoPilot.
 * Unknown properties are ignored so a newer Ollama release adding fields does not break
 * parsing.
 */
final class OllamaWire {

    private OllamaWire() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TagsResponse(List<ModelEntry> models) {
        List<String> modelNames() {
            if (models == null) {
                return List.of();
            }
            return models.stream()
                    .map(entry -> entry.name() == null ? entry.model() : entry.name())
                    .filter(name -> name != null && !name.isBlank())
                    .toList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelEntry(String name, String model, Long size) {
    }

    record ChatMessage(String role, String content) {
    }

    record ChatOptions(Double temperature, @JsonProperty("num_predict") Integer numPredict) {
    }

    record ChatRequestBody(
            String model,
            List<ChatMessage> messages,
            boolean stream,
            ChatOptions options,
            /** "json" asks Ollama to constrain output to a single JSON object. */
            String format) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponseBody(
            String model,
            ChatMessage message,
            Boolean done,
            @JsonProperty("done_reason") String doneReason,
            @JsonProperty("prompt_eval_count") Integer promptEvalCount,
            @JsonProperty("eval_count") Integer evalCount) {

        String content() {
            return message == null ? null : message.content();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String error) {
    }

    record EmbedRequestBody(String model, String input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponseBody(List<List<Double>> embeddings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LegacyEmbedResponseBody(List<Double> embedding) {
    }
}
