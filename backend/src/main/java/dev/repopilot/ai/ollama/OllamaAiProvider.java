package dev.repopilot.ai.ollama;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.ai.AiFailureKind;
import dev.repopilot.ai.AiProvider;
import dev.repopilot.ai.AiProviderException;
import dev.repopilot.ai.ModelAvailability;
import dev.repopilot.ai.model.ChatCompletion;
import dev.repopilot.ai.model.ChatRequest;
import dev.repopilot.config.RepoPilotProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Ollama adapter — the default local model runtime.
 *
 * <h2>Why Ollama first</h2>
 * It is local, requires no account, no API key and no egress, and is the only backend
 * that satisfies RepoPilot's local-first requirement out of the box. Every failure mode
 * is therefore a normal operating condition: the daemon may simply not be running.
 *
 * <h2>Failure translation</h2>
 * Ollama's errors are turned into {@link AiFailureKind} values so the caller can show
 * the right thing:
 * <ul>
 *   <li>connection refused or DNS failure → {@link AiFailureKind#UNAVAILABLE}, which the
 *       UI renders as the model setup guide;</li>
 *   <li>404, or a 400 whose body mentions the model name → {@link AiFailureKind#MODEL_NOT_FOUND},
 *       which is actionable ("pull the model") rather than generic;</li>
 *   <li>a deadline exceeded → {@link AiFailureKind#TIMEOUT}.</li>
 * </ul>
 *
 * <p>No code path in this class fabricates a completion. If the daemon is absent, the
 * result is an error, never invented text.
 */
public class OllamaAiProvider implements AiProvider {

    public static final String PROVIDER_ID = "ollama";

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final String chatModel;
    private final String embeddingModel;
    private final Duration timeout;
    private final boolean embeddingsEnabled;

    public OllamaAiProvider(WebClient.Builder builder,
                            ObjectMapper objectMapper,
                            RepoPilotProperties.Ai properties) {
        this.client = builder.clone().baseUrl(properties.baseUrl()).build();
        this.objectMapper = objectMapper;
        this.chatModel = properties.chatModel();
        this.embeddingModel = properties.embeddingModel();
        this.timeout = Duration.ofSeconds(Math.max(5, properties.requestTimeoutSeconds()));
        this.embeddingsEnabled = properties.embeddingsEnabled()
                && properties.embeddingModel() != null
                && !properties.embeddingModel().isBlank();
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Ollama (local)";
    }

    @Override
    public String chatModel() {
        return chatModel;
    }

    @Override
    public boolean supportsEmbeddings() {
        return embeddingsEnabled;
    }

    @Override
    public Mono<ModelAvailability> checkAvailability() {
        Instant checkedAt = Instant.now();
        return client.get()
                .uri("/api/tags")
                .retrieve()
                .bodyToMono(OllamaWire.TagsResponse.class)
                .timeout(timeout)
                .map(tags -> {
                    List<String> models = tags == null ? List.of() : tags.modelNames();
                    boolean present = models.stream().anyMatch(this::sameModel);
                    String message = present
                            ? "Ollama is reachable and the configured model is installed."
                            : "Ollama is reachable but the configured model '" + chatModel
                                    + "' is not installed. Run: ollama pull " + chatModel;
                    return new ModelAvailability(PROVIDER_ID, true, chatModel, present,
                            models.stream().limit(50).toList(), message, checkedAt);
                })
                // checkAvailability must never fail: an unreachable provider is a state to
                // report, and a thrown error here would take down the health endpoint.
                .onErrorResume(error -> Mono.just(new ModelAvailability(
                        PROVIDER_ID, false, chatModel, false, List.of(),
                        describeUnavailable(error), checkedAt)));
    }

    @Override
    public Mono<ChatCompletion> complete(ChatRequest request) {
        var body = new OllamaWire.ChatRequestBody(
                resolveModel(request),
                request.messages().stream()
                        .map(message -> new OllamaWire.ChatMessage(message.role().wireName(), message.content()))
                        .toList(),
                false,
                new OllamaWire.ChatOptions(request.temperature(), request.maxTokens()),
                request.jsonMode() ? "json" : null);

        Instant startedAt = Instant.now();

        return client.post()
                .uri("/api/chat")
                .bodyValue(body)
                .exchangeToMono(response -> handleChatResponse(response, request, startedAt))
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, error -> new AiProviderException(
                        AiFailureKind.TIMEOUT, PROVIDER_ID,
                        "The model did not respond within " + timeout.toSeconds()
                                + " seconds. Increase repopilot.ai.request-timeout-seconds, or use a smaller model.",
                        error))
                .onErrorMap(WebClientRequestException.class, error -> new AiProviderException(
                        AiFailureKind.UNAVAILABLE, PROVIDER_ID, describeUnavailable(error), error))
                .onErrorMap(error -> !(error instanceof AiProviderException), error -> new AiProviderException(
                        AiFailureKind.PROVIDER_ERROR, PROVIDER_ID,
                        "The model provider returned an unexpected error.", error));
    }

    @Override
    public Mono<float[]> embed(String text) {
        if (!supportsEmbeddings()) {
            return Mono.error(new UnsupportedOperationException(
                    "Embeddings are disabled. Set repopilot.ai.embeddings-enabled=true and configure "
                            + "repopilot.ai.embedding-model to enable semantic retrieval. Lexical retrieval "
                            + "works without them."));
        }
        return client.post()
                .uri("/api/embed")
                .bodyValue(new OllamaWire.EmbedRequestBody(embeddingModel, text))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(OllamaWire.EmbedResponseBody.class)
                                .map(body -> firstEmbedding(body));
                    }
                    return response.createException().flatMap(Mono::error);
                })
                .timeout(timeout)
                .onErrorMap(TimeoutException.class, error -> new AiProviderException(
                        AiFailureKind.TIMEOUT, PROVIDER_ID, "The embedding model did not respond in time.", error))
                .onErrorMap(WebClientRequestException.class, error -> new AiProviderException(
                        AiFailureKind.UNAVAILABLE, PROVIDER_ID, describeUnavailable(error), error));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Mono<ChatCompletion> handleChatResponse(ClientResponse response,
                                                    ChatRequest request,
                                                    Instant startedAt) {
        HttpStatusCode status = response.statusCode();
        if (!status.is2xxSuccessful()) {
            return response.bodyToMono(String.class).defaultIfEmpty("")
                    .flatMap(body -> Mono.error(translateHttpError(status, body, request)));
        }
        return response.bodyToMono(OllamaWire.ChatResponseBody.class)
                .map(body -> toCompletion(body, request, startedAt));
    }

    private ChatCompletion toCompletion(OllamaWire.ChatResponseBody body,
                                        ChatRequest request,
                                        Instant startedAt) {
        String content = body == null ? null : body.content();
        if (content == null || content.isBlank()) {
            throw new AiProviderException(AiFailureKind.INVALID_RESPONSE, PROVIDER_ID,
                    "The model returned an empty response. This usually means the configured model "
                            + "does not support chat completion. Configured model: " + resolveModel(request));
        }
        // Ollama reports 'length' when num_predict was reached. A truncated answer is
        // still returned, but flagged so the caller can warn the user rather than
        // silently presenting an incomplete analysis.
        boolean truncated = body.doneReason() != null
                && body.doneReason().equalsIgnoreCase("length");

        return new ChatCompletion(
                content,
                body.model() == null ? resolveModel(request) : body.model(),
                body.promptEvalCount(),
                body.evalCount(),
                Duration.between(startedAt, Instant.now()),
                truncated,
                Map.of("doneReason", body.doneReason() == null ? "" : body.doneReason()));
    }

    private AiProviderException translateHttpError(HttpStatusCode status, String body, ChatRequest request) {
        String providerMessage = extractErrorMessage(body);
        String model = resolveModel(request);

        if (status.value() == 404) {
            return new AiProviderException(AiFailureKind.MODEL_NOT_FOUND, PROVIDER_ID,
                    "Ollama does not have the model '" + model + "'. Run: ollama pull " + model);
        }
        if (status.value() == 401 || status.value() == 403) {
            return new AiProviderException(AiFailureKind.UNAUTHORIZED, PROVIDER_ID,
                    "Ollama rejected the request as unauthorised.");
        }
        if (status.value() == 429) {
            return new AiProviderException(AiFailureKind.RATE_LIMITED, PROVIDER_ID,
                    "Ollama is throttling requests. Reduce repopilot.ai.max-concurrent-requests.");
        }
        if (status.is4xxClientError()) {
            // Ollama reports an unknown model as 400 with the model name in the message.
            if (providerMessage.toLowerCase(java.util.Locale.ROOT).contains(model.toLowerCase(java.util.Locale.ROOT))
                    && providerMessage.toLowerCase(java.util.Locale.ROOT).contains("not found")) {
                return new AiProviderException(AiFailureKind.MODEL_NOT_FOUND, PROVIDER_ID,
                        "Ollama does not have the model '" + model + "'. Run: ollama pull " + model);
            }
            return new AiProviderException(AiFailureKind.BAD_REQUEST, PROVIDER_ID,
                    "Ollama rejected the request: " + providerMessage);
        }
        return new AiProviderException(AiFailureKind.PROVIDER_ERROR, PROVIDER_ID,
                "Ollama returned an error: " + providerMessage);
    }

    /**
     * Extracts Ollama's own error text. Bounded and stripped of control characters: the
     * value comes from an external process and is echoed to the user.
     */
    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "no detail supplied";
        }
        try {
            OllamaWire.ErrorBody parsed = objectMapper.readValue(body, OllamaWire.ErrorBody.class);
            if (parsed != null && parsed.error() != null && !parsed.error().isBlank()) {
                return sanitise(parsed.error());
            }
        } catch (Exception ignored) {
            // Fall through to the raw-body branch; a non-JSON error body is still useful.
        }
        return sanitise(body);
    }

    private static String sanitise(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}\\p{Cf}&&[^\\n\\t]]", "").replaceAll("\\s+", " ").trim();
        return cleaned.length() > 300 ? cleaned.substring(0, 300) + "…" : cleaned;
    }

    private String describeUnavailable(Throwable error) {
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return "Ollama is not reachable. Start it with 'ollama serve' and confirm "
                + "repopilot.ai.base-url. Underlying error: " + sanitise(reason);
    }

    private String resolveModel(ChatRequest request) {
        return request.model() == null || request.model().isBlank() ? chatModel : request.model();
    }

    /**
     * Ollama accepts a bare name for the default tag ({@code llama3}) or a full
     * {@code name:tag}. A configured {@code llama3} should match an available
     * {@code llama3:latest}, otherwise the model list check produces a confusing
     * false negative.
     */
    private boolean sameModel(String available) {
        if (available == null) {
            return false;
        }
        String normalisedAvailable = available.replace(":latest", "");
        String normalisedConfigured = chatModel == null ? "" : chatModel.replace(":latest", "");
        return normalisedAvailable.equals(normalisedConfigured);
    }

    private static float[] firstEmbedding(OllamaWire.EmbedResponseBody body) {
        if (body == null || body.embeddings() == null || body.embeddings().isEmpty()) {
            throw new AiProviderException(AiFailureKind.INVALID_RESPONSE, PROVIDER_ID,
                    "The embedding model returned no vector.");
        }
        List<Double> values = body.embeddings().get(0);
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i).floatValue();
        }
        return result;
    }

    /** Package-private for tests: assert the request body shape without a live daemon. */
    OllamaWire.ChatRequestBody buildRequestBody(ChatRequest request) {
        return new OllamaWire.ChatRequestBody(
                resolveModel(request),
                request.messages().stream()
                        .map(message -> new OllamaWire.ChatMessage(
                                message.role().wireName(), message.content()))
                        .toList(),
                false,
                new OllamaWire.ChatOptions(request.temperature(), request.maxTokens()),
                request.jsonMode() ? "json" : null);
    }
}
