package com.example.quality;

import java.util.ArrayList;
import java.util.List;

/**
 * Deliberate fixture defects. See ../README.md.
 *
 * <p>Each method below isolates one construct so that a finding reported by RepoPilot can be
 * matched to the line that produced it. Nothing here is a real bug in a real program; it is the
 * input that makes the analysis observable.
 */
public class OrderImporter {

    private final List<String> imported = new ArrayList<>();

    /** RP001: the catch block is empty, so a rejected row disappears without trace. */
    public int importAll(List<String> rows) {
        int count = 0;
        for (String row : rows) {
            try {
                imported.add(parse(row));
                count++;
            } catch (RuntimeException e) {
            }
        }
        return count;
    }

    /** RP002: the handler runs, but the caught exception is never read. */
    public boolean check(String row) {
        boolean ok = false;
        try {
            ok = parse(row) != null;
        } catch (IllegalArgumentException e) {
            ok = false;
        }
        return ok;
    }

    /** RP003 and RP004: the failure goes to stderr and to stdout instead of the log. */
    public void report(String row) {
        try {
            parse(row);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("failed: " + row);
        }
    }

    /** RP008 and RP004: Throwable includes Error, which is not recoverable. */
    public void flush() {
        try {
            parse("");
        } catch (Throwable t) {
            System.err.println("unreachable: " + t.getMessage());
        }
    }

    /** RP006: each iteration holds its thread for the whole sleep. */
    public void waitForUpstream() throws InterruptedException {
        int attempts = 5;
        while (attempts-- > 0) {
            if (imported.isEmpty()) {
                Thread.sleep(200);
            }
        }
    }

    private String parse(String row) {
        if (row == null || row.isBlank()) {
            throw new IllegalArgumentException("empty row");
        }
        return row.trim();
    }
}
