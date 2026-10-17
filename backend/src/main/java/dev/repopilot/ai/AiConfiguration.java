package dev.repopilot.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.ai.ollama.OllamaAiProvider;
import dev.repopilot.config.RepoPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires AI providers.
 *
 * <p>Provider construction is explicit rather than reflective. RepoPilot ships two
 * adapters and no plugin system, so an explicit factory is clearer and lets each adapter
 * validate its own configuration at construction time — a misconfigured cloud provider
 * fails at startup with a specific message instead of at first use with a generic one.
 *
 * <p>The OpenAI-compatible adapter is only constructed when it is enabled <em>and</em>
 * complete. A half-configured cloud provider is a configuration error that is logged
 * clearly and then ignored, so it cannot break the local-first default experience.
 */
@Configuration
public class AiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiConfiguration.class);

    @Bean
    OllamaAiProvider ollamaAiProvider(WebClient.Builder builder,
                                      ObjectMapper objectMapper,
                                      RepoPilotProperties properties) {
        return new OllamaAiProvider(builder, objectMapper, properties.ai());
    }
}
