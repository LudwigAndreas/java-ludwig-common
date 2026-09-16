package ru.ludwigandreas.observability.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.observability.tracing.ForcedSamplingHint;

/**
 * Turns the force-trace request header into a thread-scoped sampling hint.
 *
 * <h2>Ordering is the whole design</h2>
 *
 * <p>This filter must run at {@link Ordered#HIGHEST_PRECEDENCE}, ahead of Spring Boot's
 * {@code ServerHttpObservationFilter} at {@code HIGHEST_PRECEDENCE + 1}. That filter starts the
 * server observation, which starts the server span, which is the moment OpenTelemetry asks the
 * sampler for a decision that then binds the entire trace. A hint set even one filter later arrives
 * after the decision it was meant to influence and does nothing at all - silently, which is the
 * worst way for a debugging aid to fail.
 *
 * <p>It is a separate filter from {@link CorrelationIdFilter} for exactly that reason: the two have
 * opposite ordering requirements. This one has to precede span creation; the correlation filter has
 * to follow it, because it seeds ids from the trace. Merging them would mean sacrificing one.
 */
public class ForceSamplingHintFilter extends OncePerRequestFilter implements Ordered {

    private final String headerName;

    public ForceSamplingHintFilter(String headerName) {
        this.headerName = headerName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isForceRequested(request)) {
            chain.doFilter(request, response);
            return;
        }
        try (ForcedSamplingHint.Scope ignored = ForcedSamplingHint.force()) {
            chain.doFilter(request, response);
        }
    }

    /**
     * Only an explicit "true" or "1" forces sampling.
     *
     * <p>Treating mere presence as truth would make {@code X-Ludwig-Force-Trace: false} force
     * sampling, which is the opposite of what anyone writing that header means - and the sort of
     * thing that is discovered months later as an unexplained 100% sampling rate.
     */
    private boolean isForceRequested(HttpServletRequest request) {
        String value = request.getHeader(headerName);
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
