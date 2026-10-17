package dev.repopilot;

import dev.repopilot.config.RepoPilotProperties;
import dev.repopilot.config.StorageInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RepoPilot application entry point.
 *
 * <p>Architectural note: this is intentionally a modular monolith. The
 * {@code dev.repopilot.*} packages are the logical module boundaries described in
 * docs/architecture.md. They are kept as packages rather than separate Maven
 * modules until there is a measured build-time or ownership reason to split them.
 */
@SpringBootApplication
@EnableConfigurationProperties(RepoPilotProperties.class)
@EnableScheduling
public class RepoPilotApplication {

    private static final Logger log = LoggerFactory.getLogger(RepoPilotApplication.class);

    public static void main(String[] args) {
        // Fail fast with an actionable message rather than an obscure error when
        // the runtime is too old. RepoPilot uses records, pattern matching and
        // virtual-thread-friendly APIs throughout.
        int feature = Runtime.version().feature();
        if (feature < 21) {
            throw new IllegalStateException(
                    "RepoPilot requires Java 21 or newer. Detected Java " + feature + ".");
        }

        var context = SpringApplication.run(RepoPilotApplication.class, args);
        var storage = context.getBean(StorageInitializer.class);
        log.info("RepoPilot {} started. Data directory: {}",
                context.getEnvironment().getProperty("repopilot.version", "0.1.0"),
                storage.dataDirectory().toAbsolutePath());
    }
}
