package dev.repopilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * RepoPilot never calls {@code Instant.now()} in domain code. Time is injected so
 * that retention rules, job staleness detection and revision timestamps can be
 * tested deterministically without sleeping.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
