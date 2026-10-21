package dev.repopilot.ai;

import dev.repopilot.ai.model.ChatCompletion;
import dev.repopilot.ai.model.ChatRequest;
import dev.repopilot.config.RepoPilotProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Selects and protects the active {@link AiProvider}.
 *
 * <h2>Resilience applied here, not in providers</h2>
 * Circuit breaking and concurrency limiting are cross-provider concerns, so they live in
 * one place rather than being re-implemented per adapter:
 *
 * <ul>
 *   <li><b>Bulkhead (semaphore).</b> At most
 *       {@code repopilot.ai.max-concurrent-requests} model calls run at once. A local
 *       model runtime serves one request at a time in practice; flooding it makes every
 *       request slower and can exhaust its memory. A semaphore rather than a thread pool
 *       because the work is non-blocking I/O.</li>
 *   <li><b>Circuit breaker.</b> Once the provider is clearly failing, calls fail fast with
 *       an actionable "AI is unavailable" error instead of each one waiting for a timeout.
 *       This is what keeps static analysis and code browsing responsive while the model
 *       runtime is down.</li>
 *   <li><b>No retry.</b> RepoPilot does not retry model calls automatically. A retry
 *       multiplies load on a struggling local runtime and, for a deterministic failure such
 *       as invalid JSON, produces the identical result. Retrying is a user action.</li>
 * </ul>
 *
 * <h2>Graceful degradation</h2>
 * {@link #active()} returns empty when AI is disabled or no provider could be built. Every
 * caller must handle that state; the UI renders a setup guide, and static analysis
 * continues to work.
 */
@Component
public class AiProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiProviderRegistry.class);

    /** Availability probes are cached for this long to avoid hammering a local runtime. */
    private static final Duration AVAILABILITY_CACHE_TTL = Duration.ofSeconds(15);

    private final Map<String, AiProvider> providers = new LinkedHashMap<>();
    private final RepoPilotProperties.Ai properties;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final AtomicReference<CachedAvailability> availabilityCache = new AtomicReference<>();

    public AiProviderRegistry(List<AiProvider> discoveredProviders,
                              RepoPilotProperties properties) {
        this.properties = properties.ai();

        if (properties.ai().enabled()) {
            for (AiProvider provider : discoveredProviders) {
                providers.put(provider.id(), provider);
            }
        } else {
            log.info("AI features are disabled by configuration (repopilot.ai.enabled=false). "
                    + "Static analysis and code intelligence remain fully available.");
        }

        int maxConcurrent = Math.max(1, this.properties.maxConcurrentRequests());
        this.bulkhead = Bulkhead.of("aiProvider", BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ofSeconds(5))
                .writableStackTraceEnabled(false)
                .build());

        this.circuitBreaker = CircuitBreaker.of("aiProvider", CircuitBreakerConfig.custom()
                // A local runtime that is not running fails immediately and repeatedly, so a
                // small sliding window is sufficient to open the circuit.
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(60f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .writableStackTraceEnabled(false)
                .build());

        log.info("AI registry ready: enabled={}, activeProvider={}, providers={}, bulkheadLimit={}",
                this.properties.enabled(), activeProviderId(), providers.keySet(), maxConcurrent);
    }

    /** The configured provider id, whether or not it could be built. */
    public String activeProviderId() {
        return properties.provider() == null ? "none" : properties.provider();
    }

    /** The active provider, or empty when AI is unavailable. */
    public Optional<AiProvider> active() {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(properties.provider()));
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * A user-facing explanation for "there is no provider to call", naming the provider and the
     * setting that selects it. Shared by the exception path and by pre-flight checks, so a
     * refused investigation and a failed one say the same thing.
     */
    public String unavailableMessage() {
        if (!properties.enabled()) {
            return "AI features are disabled. Set repopilot.ai.enabled=true to enable investigations.";
        }
        return "The configured AI provider '" + activeProviderId() + "' is not available in this "
                + "installation. Check repopilot.ai.provider and repopilot.ai.base-url, and make sure "
                + "the provider is running.";
    }

    /** Provider ids that were successfully constructed. */
    public List<String> availableProviderIds() {
        return List.copyOf(providers.keySet());
    }

    public boolean isEmbeddingsEnabled() {
        return active().map(AiProvider::supportsEmbeddings).orElse(false);
    }

    /**
     * Runs a completion through the bulkhead and circuit breaker.
     *
     * @throws AiProviderException with {@link AiFailureKind#NOT_CONFIGURED} when no provider
     *                             is available, and {@link AiFailureKind#UNAVAILABLE} when
     *                             the circuit is open or the bulkhead is saturated
     */
    public Mono<ChatCompletion> complete(ChatRequest request) {
        AiProvider provider = active().orElseThrow(() -> new AiProviderException(
                AiFailureKind.NOT_CONFIGURED, activeProviderId(), unavailableMessage()));

        return Mono.defer(() -> provider.complete(request))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .onErrorMap(error -> {
                    if (error instanceof AiProviderException) {
                        return error;
                    }
                    if (error instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
                        return new AiProviderException(AiFailureKind.UNAVAILABLE, provider.id(),
                                "The AI provider is failing repeatedly, so RepoPilot has temporarily stopped "
                                        + "calling it. It will be retried automatically in about 30 seconds.", error);
                    }
                    if (error instanceof io.github.resilience4j.bulkhead.BulkheadFullException) {
                        return new AiProviderException(AiFailureKind.RATE_LIMITED, provider.id(),
                                "Too many AI requests are already in flight (limit "
                                        + properties.maxConcurrentRequests() + "). Try again shortly.", error);
                    }
                    return error;
                });
    }

    /**
     * Cached availability probe. Never fails: an unreachable provider is a reportable state.
     *
     * <p>Runs on a bounded elastic scheduler because a provider implementation may use a
     * blocking HTTP client.
     */
    public Mono<ModelAvailability> availability(boolean forceRefresh) {
        CachedAvailability cached = availabilityCache.get();
        if (!forceRefresh && cached != null && cached.isFresh()) {
            return Mono.just(cached.availability());
        }

        Optional<AiProvider> provider = active();
        if (provider.isEmpty()) {
            String message = properties.enabled()
                    ? "The configured AI provider '" + activeProviderId()
                      + "' is not available in this installation."
                    : "AI features are disabled. Set repopilot.ai.enabled=true to enable investigations.";
            return Mono.just(ModelAvailability.unavailable(activeProviderId(), message));
        }

        return Mono.defer(provider.get()::checkAvailability)
                .subscribeOn(Schedulers.boundedElastic())
                .map(availability -> {
                    availabilityCache.set(new CachedAvailability(availability, Instant.now()));
                    return availability;
                })
                .onErrorResume(error -> {
                    // checkAvailability should not fail, but a third-party implementation might.
                    ModelAvailability unavailable = ModelAvailability.unavailable(activeProviderId(),
                            "The availability probe failed: " + error.getClass().getSimpleName());
                    availabilityCache.set(new CachedAvailability(unavailable, Instant.now()));
                    return Mono.just(unavailable);
                });
    }

    /** Circuit-breaker snapshot for the diagnostics screen. */
    public Map<String, Object> resilienceSnapshot() {
        return Map.of(
                "circuitBreakerState", circuitBreaker.getState().name(),
                "circuitBreakerFailureRate", circuitBreaker.getMetrics().getFailureRate(),
                "circuitBreakerBufferedCalls", circuitBreaker.getMetrics().getNumberOfBufferedCalls(),
                "bulkheadAvailableCalls", bulkhead.getMetrics().getAvailableConcurrentCalls(),
                "bulkheadMaxCalls", bulkhead.getMetrics().getMaxAllowedConcurrentCalls());
    }

    private record CachedAvailability(ModelAvailability availability, Instant fetchedAt) {
        boolean isFresh() {
            return Instant.now().isBefore(fetchedAt.plus(AVAILABILITY_CACHE_TTL));
        }
    }
}
