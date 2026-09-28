package ru.ludwigandreas.observability.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;
import ru.ludwigandreas.observability.tracing.TraceContextAccessor;

/**
 * Establishes the correlation id for one HTTP request, publishes it to the logs, the response and
 * the current span, and restores what was there before on the way out.
 *
 * <h2>Why it runs after the observation filter</h2>
 *
 * <p>Ordered at {@code HIGHEST_PRECEDENCE + 2}, immediately after Spring Boot's
 * {@code ServerHttpObservationFilter} at {@code + 1}. By then the server span exists, and that buys
 * two things that are impossible one filter earlier: a request arriving without a correlation id can
 * adopt the trace id as its correlation id - so the two ids are identical for traffic that
 * originates here, and an engineer can paste either into either tool - and the response can carry
 * the trace id back to the caller.
 *
 * <p>Response headers are written <em>before</em> the chain proceeds, never after. A handler that
 * streams, or that commits the response early, would otherwise leave the headers silently unwritten:
 * the servlet API cannot add a header to a committed response, and it does not report the attempt as
 * an error.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    /**
     * Span tag the correlation id is attached to, so a trace can be found from a support ticket.
     *
     * <p>Not a low-cardinality observation key-value, which would become a metric tag: the id is
     * unique per request, and a metric tagged with it produces one time series per request - the
     * textbook cardinality explosion that takes a Prometheus server down.
     */
    private static final String CORRELATION_SPAN_TAG = "correlation.id";

    private final CorrelationContext correlationContext;
    private final CorrelationIdResolver resolver;
    private final TraceContextAccessor traceContext;
    private final ObservabilityProperties.Correlation properties;

    public CorrelationIdFilter(CorrelationContext correlationContext, CorrelationIdResolver resolver,
            TraceContextAccessor traceContext, ObservabilityProperties.Correlation properties) {
        this.correlationContext = correlationContext;
        this.resolver = resolver;
        this.traceContext = traceContext;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolver.resolve(inboundCandidates(request), traceContext::currentTraceIdOrNull);

        try (CorrelationContext.Scope ignored = correlationContext.open(correlationId)) {
            writeResponseHeaders(response, correlationId);
            tagCurrentSpan(correlationId);
            chain.doFilter(request, response);
        }
    }

    /** The configured header first, then the fallbacks, in declaration order. */
    private List<String> inboundCandidates(HttpServletRequest request) {
        List<String> candidates = new ArrayList<>();
        candidates.add(request.getHeader(properties.getHeaderName()));
        for (String header : properties.getAdditionalInboundHeaders()) {
            candidates.add(request.getHeader(header));
        }
        return candidates;
    }

    private void writeResponseHeaders(HttpServletResponse response, String correlationId) {
        if (properties.isIncludeResponseHeader() && correlationId != null) {
            response.setHeader(properties.getHeaderName(), correlationId);
        }
        String traceHeader = properties.getTraceIdResponseHeader();
        if (traceHeader != null && !traceHeader.isBlank()) {
            traceContext.currentTraceId().ifPresent(traceId -> response.setHeader(traceHeader, traceId));
        }
    }

    /**
     * Makes the trace findable by correlation id in the tracing backend, closing the last gap
     * between "the customer quoted an id" and "here is the trace".
     */
    private void tagCurrentSpan(String correlationId) {
        if (correlationId == null) {
            return;
        }
        traceContext.currentSpan().ifPresent(span -> span.tag(CORRELATION_SPAN_TAG, correlationId));
    }

    @Override
    public int getOrder() {
        // +2, not +1: HIGHEST_PRECEDENCE + 1 is taken by ServerHttpObservationFilter, and equal
        // order values leave the relative position of the two down to registration order.
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
