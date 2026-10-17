package dev.repopilot.security;

import dev.repopilot.config.RepoPilotProperties;
import dev.repopilot.config.StorageInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Owns the RepoPilot API token.
 *
 * <p>Threat model and decisions (see docs/security.md):
 * <ul>
 *   <li>The token is a capability. It is never logged, never returned by an API,
 *       and never embedded in the served frontend bundle.</li>
 *   <li>Comparison is performed on SHA-256 digests using
 *       {@link MessageDigest#isEqual(byte[], byte[])}, which does not short-circuit
 *       on the first differing byte and does not leak the token length.</li>
 *   <li>When no token is configured one is generated with
 *       {@link SecureRandom} and written to {@code <data-dir>/token.txt} with
 *       owner-only permissions where the platform supports POSIX permissions.</li>
 * </ul>
 *
 * <p>The generated file is the documented first-run handoff: the operator reads it
 * once and pastes it into the UI's connect screen, which stores it in
 * {@code sessionStorage} for the lifetime of the tab.
 */
@Component
public class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);

    /** Minimum length for an operator-supplied token. Short tokens are rejected at startup. */
    static final int MINIMUM_OPERATOR_TOKEN_LENGTH = 16;
    private static final int GENERATED_TOKEN_BYTES = 32;
    private static final String TOKEN_FILE_NAME = "token.txt";

    private final byte[] expectedDigest;
    private final Path tokenFile;
    private final boolean generated;

    public AuthTokenService(RepoPilotProperties properties, StorageInitializer storage) {
        this.tokenFile = storage.dataDirectory().resolve(TOKEN_FILE_NAME);
        String configured = properties.security().token();

        if (configured != null && !configured.isBlank()) {
            String trimmed = configured.trim();
            if (trimmed.length() < MINIMUM_OPERATOR_TOKEN_LENGTH) {
                throw new IllegalStateException(
                        "repopilot.security.token (or REPOPILOT_AUTH_TOKEN) must be at least "
                                + MINIMUM_OPERATOR_TOKEN_LENGTH + " characters. RepoPilot refuses to start "
                                + "with a weak, guessable token.");
            }
            this.expectedDigest = sha256(trimmed);
            this.generated = false;
            log.info("API token supplied through configuration (value not logged)");
        } else {
            String generatedToken = generateToken();
            this.expectedDigest = sha256(generatedToken);
            this.generated = true;
            writeTokenFile(generatedToken);
        }
    }

    /**
     * Constant-time verification of a presented token.
     *
     * @return true when the presented value matches the configured token
     */
    public boolean verify(String presented) {
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, sha256(presented));
    }

    /** True when RepoPilot generated the token itself on this startup. */
    public boolean isGenerated() {
        return generated;
    }

    /** Absolute path to the token file. For operator diagnostics only; never serialised to a client. */
    public Path tokenFile() {
        return tokenFile;
    }

    /**
     * A human-readable, non-secret hint describing where the operator can find the
     * token. Deliberately config-relative rather than an absolute path so API
     * responses never disclose the machine's directory layout.
     */
    public String locationHint() {
        return generated
                ? "token.txt inside the configured RepoPilot data directory"
                : "supplied through the REPOPILOT_AUTH_TOKEN environment variable or repopilot.security.token";
    }

    private String generateToken() {
        byte[] bytes = new byte[GENERATED_TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void writeTokenFile(String token) {
        try {
            Files.createDirectories(tokenFile.getParent());
            Files.writeString(tokenFile, token + System.lineSeparator(), StandardCharsets.UTF_8);
            restrictPermissions(tokenFile);
            log.info("""
                    RepoPilot generated an API token and wrote it to {}.
                    Open the UI and paste the token from that file. To supply your own token instead, \
                    set REPOPILOT_AUTH_TOKEN. The token value is never logged.""", tokenFile);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to write the generated API token to " + tokenFile
                            + ". Set REPOPILOT_AUTH_TOKEN to supply a token explicitly.", e);
        }
    }

    private void restrictPermissions(Path file) {
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Windows and some mounted filesystems have no POSIX view. The file lives
            // under the user's own profile, so this is a defence-in-depth measure only.
            log.debug("could not restrict token file permissions: {}", e.getMessage());
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming implementation.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }
}
