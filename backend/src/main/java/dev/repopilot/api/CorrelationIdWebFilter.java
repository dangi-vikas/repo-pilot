package dev.repopilot.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Binds an {@code X-Correlation-Id} to every request and echoes it in the response.
 *
 * <p>A client-supplied id is accepted only when it matches a strict character
 * class and length bound; otherwise a fresh id is generated. Untrusted header
 * values are never written back verbatim into responses or logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String supplied = exchange.getRequest().getHeaders().getFirst(Correlation.HEADER);
        String correlationId = Correlation.isAcceptable(supplied) ? supplied : Correlation.nextFallback();

        Correlation.bind(exchange, correlationId);
        exchange.getResponse().getHeaders().set(Correlation.HEADER, correlationId);

        return chain.filter(exchange)
                .contextWrite(Context.of(CorrelationContext.class, new CorrelationContext(correlationId)));
    }

    /**
     * Reactor-context carrier so non-web code (jobs, analyzers) can log with the
     * same identifier without a thread local.
     */
    public record CorrelationContext(String correlationId) {
    }
}
