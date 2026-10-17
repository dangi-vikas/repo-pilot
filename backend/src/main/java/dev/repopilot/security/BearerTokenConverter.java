package dev.repopilot.security;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Extracts a bearer token from the {@code Authorization} header.
 *
 * <p>Only one credential mechanism is supported, deliberately. RepoPilot does not
 * accept the token as a query parameter, cookie or custom header, because all
 * three leak more easily (browser history, proxy logs, access logs) than an
 * Authorization header.
 *
 * <p>The raw token is placed in the credentials slot of an unauthenticated
 * {@link UsernamePasswordAuthenticationToken} and verified by
 * {@link RepoPilotAuthenticationManager}; the converter itself makes no
 * authentication decision.
 */
@Component
public class BearerTokenConverter implements ServerAuthenticationConverter {

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Mono.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return Mono.empty();
        }
        return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated("repopilot", token));
    }
}
