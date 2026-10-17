package dev.repopilot.ai;

/**
 * A normalised AI provider failure.
 *
 * <p>{@link #getMessage()} is safe to show a user: it describes what the provider did,
 * never an API key, a prompt, or repository content.
 *
 * <p>{@link #retryable()} is a property of the failure kind, not of the call site, so
 * every caller classifies failures identically. A model that returned invalid JSON will
 * not become valid on retry with the same prompt; a model server that is starting up
 * will.
 */
public class AiProviderException extends RuntimeException {

    private final AiFailureKind kind;
    private final String providerId;

    public AiProviderException(AiFailureKind kind, String providerId, String message) {
        this(kind, providerId, message, null);
    }

    public AiProviderException(AiFailureKind kind, String providerId, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.providerId = providerId;
    }

    public AiFailureKind kind() {
        return kind;
    }

    public String providerId() {
        return providerId;
    }

    /**
     * Whether retrying the identical request could plausibly succeed.
     *
     * <p>{@link AiFailureKind#TIMEOUT} and {@link AiFailureKind#RATE_LIMITED} are
     * retryable because the request itself was well-formed. {@code UNAVAILABLE} is
     * retryable only if something outside RepoPilot changes, which is why it maps to
     * "degrade gracefully and tell the user" rather than an automatic retry loop.
     */
    public boolean retryable() {
        return kind == AiFailureKind.TIMEOUT || kind == AiFailureKind.RATE_LIMITED;
    }

    /** True when the correct product response is to show setup guidance. */
    public boolean requiresOperatorAction() {
        return kind == AiFailureKind.UNAVAILABLE
                || kind == AiFailureKind.MODEL_NOT_FOUND
                || kind == AiFailureKind.UNAUTHORIZED
                || kind == AiFailureKind.NOT_CONFIGURED;
    }
}
