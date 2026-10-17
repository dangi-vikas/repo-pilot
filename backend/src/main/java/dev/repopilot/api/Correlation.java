package dev.repopilot.api;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-request correlation identifier.
 *
 * <p>Held in an exchange attribute rather than a thread local: Reactor may move a
 * request across threads, but the exchange is stable for the lifetime of the
 * exchange. Log correlation uses this value explicitly through
 * {@code doOnEach} in the filter rather than relying on MDC propagation.
 */
@Component
public final class Correlation {

    public static final String HEADER = "X-Correlation-Id";
    private static final String ATTRIBUTE = Correlation.class.getName();
    private static final AtomicLong FALLBACK_SEQUENCE = new AtomicLong();

    /** Reads the correlation id for this exchange, or {@code "-"} when absent. */
    public static String current(ServerWebExchange exchange) {
        Object value = exchange.getAttribute(ATTRIBUTE);
        return value instanceof String s ? s : "-";
    }

    /** Next value for a correlation id that arrives malformed or missing. */
    static String nextFallback() {
        return "rp-" + Long.toHexString(System.nanoTime())
                + "-" + FALLBACK_SEQUENCE.incrementAndGet();
    }

    static void bind(ServerWebExchange exchange, String correlationId) {
        exchange.getAttributes().put(ATTRIBUTE, correlationId);
    }

    /** Sanitises a client-supplied correlation id: bounded length, safe character class. */
    static boolean isAcceptable(String candidate) {
        if (candidate == null || candidate.isBlank() || candidate.length() > 64) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
