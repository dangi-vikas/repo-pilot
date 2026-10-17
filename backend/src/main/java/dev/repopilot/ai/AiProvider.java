package dev.repopilot.ai;

import dev.repopilot.ai.model.ChatCompletion;
import dev.repopilot.ai.model.ChatRequest;
import reactor.core.publisher.Mono;

/**
 * The contract every model backend implements.
 *
 * <h2>Design rules</h2>
 * <ul>
 *   <li><b>No provider-specific types escape.</b> Implementations convert their own
 *       wire format into {@link ChatRequest}/{@link ChatCompletion} and translate
 *       failures into {@link AiProviderException}. The rest of RepoPilot never sees an
 *       HTTP status code from a model server.</li>
 *   <li><b>Everything is a {@link Mono}.</b> Model calls are I/O-bound and must not
 *       block a request thread. Implementations never call {@code block()}.</li>
 *   <li><b>Absence is a supported state.</b> A provider that is unreachable reports it
 *       through {@link #checkAvailability()} and a typed failure. It must never return
 *       fabricated content, and RepoPilot must remain useful without it.</li>
 *   <li><b>Timeouts and cancellation are the implementation's responsibility</b>, since
 *       only the implementation knows its transport.</li>
 *   <li><b>No credentials in errors.</b> Provider messages are user-visible.</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe: one instance serves concurrent investigations.
 */
public interface AiProvider {

    /** Stable identifier, used in configuration and in persisted investigation records. */
    String id();

    /** Human-readable name for the Settings screen. */
    String displayName();

    /** The model this provider is configured to use for chat. */
    String chatModel();

    /** Whether {@link #embed} is supported by this provider and configuration. */
    boolean supportsEmbeddings();

    /**
     * Probes the provider. Must not throw: an unreachable provider is an expected,
     * reportable state, not an error.
     */
    Mono<ModelAvailability> checkAvailability();

    /**
     * Runs a completion.
     *
     * @throws AiProviderException with a normalised {@link AiFailureKind} on any failure
     */
    Mono<ChatCompletion> complete(ChatRequest request);

    /**
     * Computes an embedding vector.
     *
     * @throws UnsupportedOperationException when {@link #supportsEmbeddings()} is false
     */
    default Mono<float[]> embed(String text) {
        return Mono.error(new UnsupportedOperationException(
                "Provider " + id() + " does not support embeddings in this configuration"));
    }
}
