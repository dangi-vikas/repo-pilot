package dev.repopilot.ai;

/**
 * Normalised classification of an AI provider failure.
 *
 * <p>Every provider implementation translates its own wire-level errors into one of
 * these. Without this, the rest of RepoPilot would need provider-specific error
 * handling, and a caller could not distinguish "the model server is not running"
 * from "the model returned nonsense" — which require completely different responses
 * (show a setup guide versus retry or rephrase).
 */
public enum AiFailureKind {

    /** The provider endpoint could not be reached or refused the connection. */
    UNAVAILABLE,
    /** The configured model is not present on the provider. */
    MODEL_NOT_FOUND,
    /** The request exceeded the configured timeout. */
    TIMEOUT,
    /** The provider returned a throttling response. */
    RATE_LIMITED,
    /** Authentication or authorisation failed at the provider. */
    UNAUTHORIZED,
    /** The provider returned a well-formed response that RepoPilot cannot interpret. */
    INVALID_RESPONSE,
    /** The request was rejected as malformed by the provider. */
    BAD_REQUEST,
    /** The call was cancelled, typically because the enclosing job was cancelled. */
    CANCELLED,
    /** The provider reported an internal error. */
    PROVIDER_ERROR,
    /** No provider is configured or the feature is switched off. */
    NOT_CONFIGURED
}
