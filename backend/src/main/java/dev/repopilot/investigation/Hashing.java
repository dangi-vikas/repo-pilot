package dev.repopilot.investigation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers shared by the prompt, excerpt and repository fingerprints. */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; reaching this is a broken JRE.
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
