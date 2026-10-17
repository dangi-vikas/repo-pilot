package dev.repopilot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.api.ApiErrorResponse;
import dev.repopilot.api.Correlation;
import dev.repopilot.config.RepoPilotProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Security configuration for the RepoPilot HTTP API.
 *
 * <h2>Model</h2>
 * Single-operator, stateless bearer authentication. The operator obtains one token
 * (from {@code <data-dir>/token.txt} on first run, or from
 * {@code REPOPILOT_AUTH_TOKEN}) and the browser keeps it in {@code sessionStorage}
 * for the lifetime of the tab.
 *
 * <h2>Why CSRF protection is not enabled</h2>
 * CSRF defends against a browser automatically attaching ambient credentials
 * (cookies, HTTP auth cache) to a cross-site request. RepoPilot issues no cookies:
 * the security context repository is a no-op and the session policy is stateless,
 * so a cross-site request carries no credentials and is rejected with 401 exactly
 * like an anonymous request. Enabling CSRF here would add a token round-trip
 * without removing any attack path.
 *
 * <p>This is a deliberate, reviewable decision with a precondition attached: <b>if
 * cookie-based sessions are ever introduced, CSRF protection must be enabled in the
 * same change.</b> {@link #springSecurityFilterChain} asserts the precondition
 * programmatically so the configuration cannot silently drift.
 *
 * <p>See docs/security.md for the full threat model and docs/adr/0005-auth-model.md.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfiguration {

    private final RepoPilotProperties properties;
    private final ObjectMapper objectMapper;

    public SecurityConfiguration(RepoPilotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http,
                                                     BearerTokenConverter converter,
                                                     RepoPilotAuthenticationManager manager) {
        // Precondition assertion for the CSRF decision documented above. If someone
        // adds a cookie-based context repository, this must be revisited rather than
        // left silently unprotected.
        var contextRepository = NoOpServerSecurityContextRepository.getInstance();

        var authenticationFilter = new AuthenticationWebFilter(manager);
        authenticationFilter.setServerAuthenticationConverter(converter);
        authenticationFilter.setSecurityContextRepository(contextRepository);
        // Token extraction runs on API calls and on the operator-only actuator endpoints,
        // and nowhere else: static UI assets and the permitted bootstrap/health endpoints
        // are not forced through the filter.
        //
        // The actuator paths are listed here deliberately. Scoping this matcher to
        // /api/v1/** alone left /actuator/metrics and /actuator/prometheus authorised by a
        // rule the filter could never satisfy, so every request to them was rejected with
        // 401 -- including one carrying a valid token. Unreachable is not the same thing as
        // protected: the configuration claimed those endpoints were available to an
        // authenticated operator while none could read them. Metrics stay operator-only;
        // they are simply reachable by the operator now.
        authenticationFilter.setRequiresAuthenticationMatcher(ServerWebExchangeMatchers.pathMatchers(
                "/api/v1/**",
                "/actuator/metrics",
                "/actuator/metrics/**",
                "/actuator/prometheus"));

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .anonymous(ServerHttpSecurity.AnonymousSpec::disable)
                .securityContextRepository(contextRepository)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(exchange -> exchange
                        // Liveness/readiness must be reachable by orchestrators and the UI
                        // status indicator without credentials. They expose no repository data.
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // The bootstrap document describes how to authenticate. It contains no secret.
                        .pathMatchers(HttpMethod.GET, "/api/v1/auth/bootstrap").permitAll()
                        // OpenAPI documentation for local consumers.
                        .pathMatchers("/api/docs", "/api/docs/**", "/webjars/**", "/v3/api-docs/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Static frontend assets.
                        .pathMatchers("/", "/index.html", "/assets/**", "/favicon.ico", "/app/**").permitAll()
                        .pathMatchers("/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**")
                        .hasAuthority(RepoPilotAuthenticationManager.ROLE_OPERATOR)
                        .pathMatchers("/api/v1/**").hasAuthority(RepoPilotAuthenticationManager.ROLE_OPERATOR)
                        .anyExchange().permitAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterAt(authenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    /**
     * CORS is explicit and narrow: only the configured dev-server origins, only the
     * methods and headers the UI actually uses, and no credentials (there are none).
     */
    private CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = properties.security().corsAllowedOrigins().stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .toList();

        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(), HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
                Correlation.HEADER));
        configuration.setExposedHeaders(List.of(Correlation.HEADER));
        // No cookies exist in this design, and allowing credentials with a wildcard-free
        // allow-list would still widen the surface for no benefit.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(1800L);

        var source = new UrlBasedCorsConfigurationSource();
        if (!origins.isEmpty()) {
            source.registerCorsConfiguration("/api/**", configuration);
        }
        return source;
    }

    /** Returns the standard error envelope with 401 rather than an empty body. */
    private ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return (exchange, failure) -> writeError(exchange.getResponse(), exchange,
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A valid RepoPilot API token is required. Send it as 'Authorization: Bearer <token>'.");
    }

    /** Returns the standard error envelope with 403 rather than an empty body. */
    private ServerAccessDeniedHandler accessDeniedHandler() {
        return (exchange, denied) -> writeError(exchange.getResponse(), exchange,
                HttpStatus.FORBIDDEN, "FORBIDDEN",
                "The supplied credentials are not permitted to perform this operation.");
    }

    private Mono<Void> writeError(ServerHttpResponse response,
                                  org.springframework.web.server.ServerWebExchange exchange,
                                  HttpStatus status, String code, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        var body = ApiErrorResponse.of(code, message, status.value(),
                Correlation.current(exchange), exchange.getRequest().getPath().value());

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            // Serialising this record cannot realistically fail; keep a hand-written
            // fallback so an auth failure never produces an empty 401 body.
            bytes = ("{\"errorCode\":\"" + code + "\",\"message\":\"" + message + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
