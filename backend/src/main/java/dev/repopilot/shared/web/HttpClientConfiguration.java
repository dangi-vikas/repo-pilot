package dev.repopilot.shared.web;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Applies RepoPilot's outbound HTTP policy to every {@link WebClient}.
 *
 * <p>Outbound calls in RepoPilot are made to the local model runtime and to the
 * optional OpenAI-compatible endpoint. Both are treated as unreliable third parties,
 * so every client gets:
 *
 * <ul>
 *   <li>a bounded connection pool with an acquire timeout, so a slow provider cannot
 *       cause unbounded socket accumulation;</li>
 *   <li>a connect timeout and a read/write timeout, so a provider that accepts a
 *       connection and then does nothing cannot hold a caller indefinitely;</li>
 *   <li>no automatic retries at the transport layer. Retry policy belongs to the
 *       caller, and nested retries (transport plus service) multiply load on a
 *       provider that is already struggling.</li>
 * </ul>
 *
 * <p>A response timeout is deliberately <em>not</em> set here. Generation latency is
 * legitimately long for a local model, so the per-call deadline is supplied by the AI
 * provider from {@code repopilot.ai.request-timeout-seconds}, where it is visible and
 * configurable.
 */
@Configuration
public class HttpClientConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(180);
    private static final int MAX_CONNECTIONS = 32;
    private static final Duration PENDING_ACQUIRE_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    WebClientCustomizer repoPilotWebClientCustomizer() {
        return builder -> builder.clientConnector(new ReactorClientHttpConnector(httpClient()));
    }

    private HttpClient httpClient() {
        var connectionProvider = ConnectionProvider.builder("repopilot-outbound")
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireTimeout(PENDING_ACQUIRE_TIMEOUT)
                .maxIdleTime(Duration.ofSeconds(30))
                .evictInBackground(Duration.ofSeconds(60))
                .build();

        return HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(READ_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(30, TimeUnit.SECONDS)))
                .compress(true)
                .keepAlive(true);
    }
}
