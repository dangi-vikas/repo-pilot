package dev.repopilot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Path;

/**
 * The HTTP boundary, asserted rather than assumed.
 *
 * <p>Three claims about this application are only true if the requests below behave as
 * written, so they are pinned here:
 *
 * <ul>
 *   <li>Liveness and readiness expose no repository data and must answer without a
 *       credential, or an orchestrator would restart a healthy process.</li>
 *   <li>Every API endpoint requires the bearer token, and presents the same 401 when it
 *       is missing or wrong. A differently-shaped error for a wrong token would leak
 *       whether a token is configured.</li>
 *   <li>Operator-only actuator endpoints ({@code /actuator/metrics},
 *       {@code /actuator/prometheus}) are authorised by the same authority the bearer
 *       token grants. They were previously unreachable by anyone, because the
 *       authentication filter was scoped to {@code /api/v1/**} while the authorisation
 *       rule asked for an authenticated operator. That failure mode is invisible to a
 *       health check, which is why it is a test now.</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "repopilot.security.token=integration-test-bearer-token-0002",
                "repopilot.ai.enabled=false"
        })
@AutoConfigureWebTestClient
class HttpSecurityBoundaryIntegrationTest {

    private static final String TOKEN = "integration-test-bearer-token-0002";

    @TempDir
    static Path workspaceRoot;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("repopilot.workspace.roots", () -> workspaceRoot.toAbsolutePath().toString());
        registry.add("repopilot.storage.data-dir", () -> dataDir.toAbsolutePath().toString());
    }

    @Autowired
    private WebTestClient client;

    @Test
    @DisplayName("liveness and readiness answer without a credential")
    void healthEndpointsArePublic() {
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("API endpoints require the bearer token")
    void apiRequiresTheBearerToken() {
        client.get().uri("/api/v1/workspaces").exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/api/v1/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .exchange()
                .expectStatus().isOk();

        // A wrong token is rejected the same way a missing one is: the response must not
        // reveal that the token exists but was incorrect.
        client.get().uri("/api/v1/workspaces")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-the-configured-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("operator-only actuator endpoints are reachable by the operator token")
    void operatorActuatorEndpointsRequireTheBearerToken() {
        client.get().uri("/actuator/metrics").exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/actuator/metrics")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.names").isArray();

        client.get().uri("/actuator/prometheus")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .exchange()
                .expectStatus().isOk();
    }
}
