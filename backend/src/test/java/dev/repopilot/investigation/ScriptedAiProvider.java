package dev.repopilot.investigation;

import dev.repopilot.ai.AiProvider;
import dev.repopilot.ai.AiProviderException;
import dev.repopilot.ai.AiFailureKind;
import dev.repopilot.ai.ModelAvailability;
import dev.repopilot.ai.model.ChatCompletion;
import dev.repopilot.ai.model.ChatRequest;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A provider that returns a canned response, for tests that need a model to misbehave.
 *
 * <p>Test scope only. It exists so the citation checker can be exercised against the failures a real
 * model produces — a key that was never sent, a claim with no citation, a line number the evidence
 * does not cover — without any of those failures being baked into production code or requiring a
 * model runtime on the test machine.
 */
public final class ScriptedAiProvider implements AiProvider {

    public static final String PROVIDER_ID = "scripted";
    public static final String MODEL = "scripted-model";

    private static volatile String response = "{}";
    private static volatile AiProviderException failure;

    /** The exact text the next call returns. */
    public static void respondWith(String content) {
        response = content;
        failure = null;
    }

    /** The next call fails instead of answering. */
    public static void failWith(AiProviderException error) {
        failure = error;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Scripted test provider";
    }

    @Override
    public String chatModel() {
        return MODEL;
    }

    @Override
    public boolean supportsEmbeddings() {
        return false;
    }

    @Override
    public Mono<ModelAvailability> checkAvailability() {
        return Mono.just(new ModelAvailability(PROVIDER_ID, true, MODEL, true, List.of(MODEL),
                "Scripted provider for tests.", Instant.now()));
    }

    @Override
    public Mono<ChatCompletion> complete(ChatRequest request) {
        AiProviderException scripted = failure;
        if (scripted != null) {
            return Mono.error(scripted);
        }
        return Mono.just(new ChatCompletion(response, MODEL, null, null, Duration.ZERO,
                false, Map.of("finishReason", "scripted")));
    }

    /** Convenience for the common "the provider is down" case. */
    public static AiProviderException unavailable() {
        return new AiProviderException(AiFailureKind.UNAVAILABLE, PROVIDER_ID,
                "The scripted provider is unreachable.");
    }
}
