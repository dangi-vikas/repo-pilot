package com.example.quality;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Deliberate fixture defects. See ../README.md.
 *
 * <p>The credential values below are fabrications. RP005 reports the location and the name of the
 * field or variable, never the value, so a screenshot of a finding cannot leak even a fake secret.
 */
public class LegacyConfig {

    /** RP005: a credential in a literal, held in a field. */
    private String apiKey = "demo-api-key-not-a-real-secret";

    /** RP007: SimpleDateFormat is not thread-safe, and this one is shared as a field. */
    private final SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd");

    /** Reported by PMD's ruleset, not by RepoPilot's own rules: written once, never read. */
    private int unusedRetries;

    public String describe() {
        String token = "demo-token-not-a-real-secret";
        return "configured with " + token.length() + " characters on " + stamp.format(new Date());
    }

    public void setUnusedRetries(int value) {
        this.unusedRetries = value;
    }
}
