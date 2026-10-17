package dev.repopilot.observability;

import dev.repopilot.ai.AiProviderRegistry;
import dev.repopilot.ai.ModelAvailability;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Readiness contributor for the model runtime.
 *
 * <h2>Why the status is UP even when the provider is absent</h2>
 * This is the most important detail in the file. RepoPilot's AI features are optional by
 * design: code intelligence, the dependency graph, static analysis, JFR analysis and
 * thread-dump analysis all work without any model. Reporting {@code DOWN} here would make
 * an orchestrator restart or de-register a perfectly healthy instance, and would tell the
 * user that RepoPilot is broken when only one optional feature is unavailable.
 *
 * <p>So the status is {@link Status#UP} with a nested {@code aiProvider} detail object that
 * states precisely whether investigations can run. A dedicated {@code OUT_OF_SERVICE}
 * style signal is available in {@link #diagnostics()} for operators who want to alert on
 * model absence specifically.
 */
@Component("aiProvider")
public class AiProviderHealthIndicator implements HealthIndicator {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private final AiProviderRegistry registry;

    public AiProviderHealthIndicator(AiProviderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        if (!registry.enabled()) {
            return Health.up()
                    .withDetail("enabled", false)
                    .withDetail("state", "disabled-by-configuration")
                    .withDetail("impact", "AI investigations are unavailable. All analysis features still work.")
                    .build();
        }

        try {
            ModelAvailability availability = registry.availability(false)
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(PROBE_TIMEOUT)
                    .block(PROBE_TIMEOUT);

            if (availability == null) {
                return degraded("The provider probe returned no result.");
            }

            Health.Builder builder = Health.up()
                    .withDetail("enabled", true)
                    .withDetail("provider", availability.providerId())
                    .withDetail("reachable", availability.reachable())
                    .withDetail("configuredModel", availability.configuredModel())
                    .withDetail("modelPresent", availability.chatModelPresent())
                    .withDetail("usable", availability.usable())
                    .withDetail("message", availability.message())
                    .withDetail("availableModelCount", availability.availableModels().size());

            for (var entry : registry.resilienceSnapshot().entrySet()) {
                builder.withDetail(entry.getKey(), entry.getValue());
            }
            return builder.build();
        } catch (RuntimeException e) {
            // A probe failure must not turn the whole application unready.
            return degraded("The availability probe did not complete: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Detail block for the diagnostics screen. Distinguishes "AI is disabled", a supported
     * state, from "AI is enabled but the runtime is missing", an actionable setup problem.
     */
    public Health diagnostics() {
        return health();
    }

    private Health degraded(String reason) {
        return Health.up()
                .withDetail("enabled", true)
                .withDetail("provider", registry.activeProviderId())
                .withDetail("usable", false)
                .withDetail("message", reason)
                .withDetail("impact", "AI investigations are unavailable. Analysis and code intelligence still work.")
                .build();
    }

    /** Exposed so the registry can be probed without a bean lookup in tests. */
    static Mono<ModelAvailability> probe(AiProviderRegistry registry) {
        return registry.availability(true);
    }
}
