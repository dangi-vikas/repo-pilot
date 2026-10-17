package dev.repopilot.ai.model;

import java.time.Duration;
import java.util.Map;

/**
 * A completion returned by a provider.
 *
 * @param content          the generated text
 * @param model            the model that actually served the request
 * @param promptTokens     input token count when the provider reports it, else null
 * @param completionTokens output token count when the provider reports it, else null
 * @param latency          wall-clock time for the call
 * @param truncated        true when generation stopped because the token limit was hit.
 *                         Surfaced because it usually means the model's answer is incomplete
 *                         and the caller should treat supporting detail as unreliable.
 * @param providerMetadata bounded diagnostic values (finish reason, provider version)
 */
public record ChatCompletion(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Duration latency,
        boolean truncated,
        Map<String, Object> providerMetadata
) {

    public ChatCompletion {
        providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
    }

    /** True when the provider returned no usable text. */
    public boolean isEmpty() {
        return content == null || content.isBlank();
    }
}
