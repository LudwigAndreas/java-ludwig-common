package ru.ludwigandreas.observability.web;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import ru.ludwigandreas.observability.correlation.CorrelationContext;

/**
 * Copies the current correlation id onto every outgoing HTTP call, so the next service sees the same
 * id this one is logging under.
 *
 * <p>Trace context is already propagated for us - Micrometer's observation instrumentation writes
 * the W3C {@code traceparent} on any client built from Spring Boot's auto-configured builder. This
 * interceptor exists because the correlation id is not trace context: it is present on unsampled
 * requests, it survives a retry that starts a new trace, and it is what a human quotes. Without this
 * the chain of logs breaks at the first outbound call even while the trace stays intact.
 *
 * <p>An existing header is left alone rather than overwritten. Code that set one deliberately - a
 * gateway forwarding a partner's id, a batch job stamping its run id - knows something this
 * interceptor does not, and silently replacing it would sever the very link it is meant to preserve.
 */
public class CorrelationPropagatingRequestInterceptor implements ClientHttpRequestInterceptor {

    private final CorrelationContext correlationContext;
    private final String headerName;

    public CorrelationPropagatingRequestInterceptor(CorrelationContext correlationContext, String headerName) {
        this.correlationContext = correlationContext;
        this.headerName = headerName;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!request.getHeaders().containsKey(headerName)) {
            correlationContext.currentId().ifPresent(id -> request.getHeaders().set(headerName, id));
        }
        return execution.execute(request, body);
    }
}
