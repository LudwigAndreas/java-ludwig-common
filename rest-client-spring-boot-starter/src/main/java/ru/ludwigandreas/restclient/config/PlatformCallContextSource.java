package ru.ludwigandreas.restclient.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.restclient.core.CallContextSource;
import ru.ludwigandreas.restclient.core.PrincipalSupplier;

/**
 * The platform's answer to "which unit of work is this call part of, and whose".
 *
 * <p>Every value is read from something that already exists: the correlation id from
 * observability-spring-boot-starter's {@code CorrelationContext} (which stores it in the MDC, so the
 * id this client propagates is provably the id the logs show), the trace id from Micrometer's
 * {@code Tracer}, and the principal from whatever {@link PrincipalSupplier} the context has.
 *
 * <p>Nothing here caches. All three are per-request values on a thread that serves many requests,
 * and a cached one would attribute a call to the previous request - which is worse than having no
 * value at all, because it looks right.
 */
public class PlatformCallContextSource implements CallContextSource {

    private final CorrelationContext correlationContext;
    private final String correlationHeaderName;
    private final ObjectProvider<Tracer> tracer;
    private final PrincipalSupplier principals;
    /** Creates the source over the platform's correlation, tracing and security contexts. */
    public PlatformCallContextSource(CorrelationContext correlationContext, String correlationHeaderName,
                                     ObjectProvider<Tracer> tracer, PrincipalSupplier principals) {
        this.correlationContext = correlationContext;
        this.correlationHeaderName = correlationHeaderName;
        this.tracer = tracer;
        this.principals = principals;
    }

    @Override
    public String correlationId() {
        return correlationContext.currentId().orElse(null);
    }

    @Override
    public String correlationHeaderName() {
        return correlationHeaderName;
    }

    @Override
    public String traceId() {
        Tracer current = tracer.getIfAvailable();
        if (current == null) {
            return null;
        }
        Span span = current.currentSpan();
        return span == null ? null : span.context().traceId();
    }

    @Override
    public String principal() {
        return principals == null ? null : principals.principal();
    }
}
