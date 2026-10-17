package dev.repopilot.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves and creates the RepoPilot data directory.
 *
 * <p>All durable derived artefacts (uploaded recordings, heap dumps, extracted
 * metadata) live under a single directory that is never inside an imported
 * repository. That keeps repository working trees clean and makes retention and
 * cleanup a single, auditable operation.
 */
@Component
public class StorageInitializer {

    private static final Logger log = LoggerFactory.getLogger(StorageInitializer.class);

    private final Path dataDirectory;
    private final Path artifactsDirectory;

    public StorageInitializer(RepoPilotProperties properties) {
        this.dataDirectory = Path.of(properties.storage().dataDir()).toAbsolutePath().normalize();
        this.artifactsDirectory = properties.storage().artifactsPath(dataDirectory).normalize();
    }

    @PostConstruct
    void initialize() {
        try {
            Files.createDirectories(dataDirectory);
            Files.createDirectories(artifactsDirectory);
            Files.createDirectories(dataDirectory.resolve("tmp"));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to create the RepoPilot data directory at " + dataDirectory
                            + ". Set repopilot.storage.data-dir to a writable location.", e);
        }
        log.info("RepoPilot storage ready (data={}, artifacts={})", dataDirectory, artifactsDirectory);
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    public Path artifactsDirectory() {
        return artifactsDirectory;
    }

    /** Scratch space for in-flight processing; wiped on startup when enabled. */
    public Path temporaryDirectory() {
        return dataDirectory.resolve("tmp");
    }
}
