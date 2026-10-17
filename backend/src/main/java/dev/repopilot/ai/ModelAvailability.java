package dev.repopilot.ai;

import java.time.Instant;
import java.util.List;

/**
 * The result of probing a provider for availability.
 *
 * @param providerId      stable provider identifier
 * @param reachable       the endpoint answered
 * @param configuredModel the model configured for chat
 * @param chatModelPresent whether the configured chat model exists on the provider
 * @param availableModels model names the provider reports, bounded
 * @param message         a short, non-secret explanation suitable for the UI
 * @param checkedAt       when the probe ran
 */
public record ModelAvailability(
        String providerId,
        boolean reachable,
        String configuredModel,
        boolean chatModelPresent,
        List<String> availableModels,
        String message,
        Instant checkedAt
) {

    /** True when RepoPilot can actually serve an investigation with this provider. */
    public boolean usable() {
        return reachable && chatModelPresent;
    }

    public static ModelAvailability unavailable(String providerId, String message) {
        return new ModelAvailability(providerId, false, null, false, List.of(), message, Instant.now());
    }
}
