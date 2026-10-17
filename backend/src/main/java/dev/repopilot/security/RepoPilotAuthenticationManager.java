package dev.repopilot.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Verifies the bearer token and, on success, produces an authenticated principal.
 *
 * <p>Failure is reported as {@link BadCredentialsException} without distinguishing
 * "no token" from "wrong token" in the response, so the API cannot be used to
 * probe token validity beyond a binary accept/reject.
 */
@Component
public class RepoPilotAuthenticationManager implements ReactiveAuthenticationManager {

    /** Single role: RepoPilot is a single-operator local tool, not a multi-tenant service. */
    public static final String ROLE_OPERATOR = "ROLE_OPERATOR";

    private final AuthTokenService tokenService;

    public RepoPilotAuthenticationManager(AuthTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        Object credentials = authentication.getCredentials();
        String presented = credentials == null ? null : credentials.toString();

        if (!tokenService.verify(presented)) {
            return Mono.error(new BadCredentialsException("Invalid API token"));
        }

        return Mono.just(UsernamePasswordAuthenticationToken.authenticated(
                "local-operator",
                null,
                List.of(new SimpleGrantedAuthority(ROLE_OPERATOR))));
    }
}
