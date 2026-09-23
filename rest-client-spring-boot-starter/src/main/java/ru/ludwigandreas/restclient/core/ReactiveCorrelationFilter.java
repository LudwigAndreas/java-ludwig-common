package ru.ludwigandreas.restclient.core;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Copies the platform correlation id onto a reactive outgoing call.
 *
 * <p>The blocking side uses observability-spring-boot-starter's own
 * {@code CorrelationPropagatingRequestInterceptor} rather than anything defined here. That module
 * has no reactive equivalent, so this filter exists - but it reuses the same id, from the same
 * source, under the same header name, both obtained from {@link CallContextSource}. Nothing about
 * how a correlation id is stored or resolved is re-decided here.
 *
 * <p>An id the caller already set is left alone, for the same reason the blocking interceptor leaves
 * it alone: code that set one deliberately - a gateway forwarding a partner's id, a batch job
 * stamping its run id - knows something this filter does not.
 */
public class ReactiveCorrelationFilter implements ExchangeFilterFunction {

    private final CallContextSource callContext;

    public ReactiveCorrelationFilter(CallContextSource callContext) {
        this.callContext = callContext;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        String header = callContext.correlationHeaderName();
        if (request.headers().containsKey(header)) {
            return next.exchange(request);
        }
        // deferContextual: in a reactive request the correlation id is read from the subscriber
        // context, so it has to be read at subscription time rather than at assembly time.
        return Mono.deferContextual(context -> {
            String id = callContext.correlationId();
            if (id == null || id.isBlank()) {
                return next.exchange(request);
            }
            return next.exchange(ClientRequest.from(request).header(header, id).build());
        });
    }
}
