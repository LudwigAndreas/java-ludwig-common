package ru.ludwigandreas.restclient.observability;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.restclient.spi.OutboundRequest;
import ru.ludwigandreas.restclient.spi.OutboundResponse;
import ru.ludwigandreas.restclient.spi.RestClientListener;

/**
 * Dispatches lifecycle callbacks to one client's listeners, and absorbs everything they throw.
 *
 * <p>The listeners are resolved and filtered once, at startup, rather than per call: asking every
 * listener in the context whether it {@code supports} this client on every request is work done
 * thousands of times per second to answer a question whose answer cannot change.
 *
 * <p><strong>A listener that throws never breaks the call.</strong> The exception is logged once per
 * listener class - the {@code reported} set is what keeps a listener that fails on every request
 * from producing one log line per request - and counted as
 * {@code ludwig.restclient.listener.failures}. Letting a dashboard integration fail a payment is not
 * a trade this platform makes; the price is that a listener cannot enforce anything, which is said
 * plainly in the SPI's own documentation.
 */
public class RestClientListeners {

    private static final Logger log = LoggerFactory.getLogger(RestClientListeners.class);

    private final String clientName;
    private final List<RestClientListener> listeners;
    private final RestClientMeters meters;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    /** Filters {@code listeners} to those supporting {@code clientName} and orders them once. */
    public RestClientListeners(String clientName, List<RestClientListener> listeners,
                               RestClientMeters meters) {
        this.clientName = clientName;
        this.listeners = listeners.stream()
                .filter(listener -> listener.supports(clientName))
                .sorted(java.util.Comparator.comparingInt(RestClientListener::getOrder))
                .toList();
        this.meters = meters;
    }

    /** Whether any listener is interested in this client, so the pipeline can skip building arguments. */
    public boolean isEmpty() {
        return listeners.isEmpty();
    }

    /** Before an attempt leaves the service. */
    public void onRequest(OutboundRequest request) {
        dispatch("onRequest", listener -> listener.onRequest(request));
    }

    /** After an attempt's status and headers have been read. */
    public void onResponse(OutboundRequest request, OutboundResponse response) {
        dispatch("onResponse", listener -> listener.onResponse(request, response));
    }

    /** When an attempt, or the logical call, ended in an exception. */
    public void onError(OutboundRequest request, Throwable error) {
        dispatch("onError", listener -> listener.onError(request, error));
    }

    /** After the decision to retry, before the wait. */
    public void onRetry(OutboundRequest request, int nextAttempt, long waitMillis, String cause) {
        dispatch("onRetry", listener -> listener.onRetry(request, nextAttempt, waitMillis, cause));
    }

    /** On a circuit-breaker transition. */
    public void onCircuitBreakerStateChange(String from, String to) {
        dispatch("onCircuitBreakerStateChange",
                listener -> listener.onCircuitBreakerStateChange(clientName, from, to));
    }

    private void dispatch(String callback, Consumer<RestClientListener> action) {
        for (RestClientListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (RuntimeException ex) {
                String name = listener.getClass().getName();
                meters.listenerFailure(clientName, name, callback);
                if (reported.add(name + '#' + callback)) {
                    log.warn("RestClientListener {} threw from {} for client {}; the call was not "
                                    + "affected. Further failures from this listener are counted by "
                                    + "ludwig.restclient.listener.failures and not logged again.",
                            name, callback, clientName, ex);
                }
            }
        }
    }
}
