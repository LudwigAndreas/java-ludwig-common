package ru.ludwigandreas.restclient.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * Names the blocking client's observation {@code ludwig.restclient.requests} and adds the
 * {@code client} tag.
 *
 * <p>Extending Spring's convention rather than replacing it is the whole point. Spring already
 * computes the tags that are hard to get right - {@code uri} from the <em>template</em> rather than
 * the expanded path, {@code status}, {@code outcome}, {@code exception}, {@code method} - and
 * already feeds the same context to the tracing bridge, so the client span and the timer describe
 * one exchange. All this class contributes is the one dimension Spring cannot know: which named
 * client made the call.
 *
 * <p>Renaming the metric is deliberate and has a cost worth stating: these calls no longer appear
 * under {@code http.client.requests}. That is the intent - a service's outbound calls through this
 * starter are a different population from whatever else it does over HTTP, they are tagged by
 * dependency, and mixing the two produces a dashboard where a slow partner and a slow health probe
 * are the same series.
 */
public class LudwigClientRequestObservationConvention extends DefaultClientRequestObservationConvention {

    private final String clientName;

    public LudwigClientRequestObservationConvention(String clientName) {
        this.clientName = clientName;
    }

    @Override
    public String getName() {
        return RestClientMeters.REQUESTS;
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
        return super.getLowCardinalityKeyValues(context).and(KeyValue.of("client", clientName));
    }

    /**
     * The span name, which is what a trace viewer shows in its list.
     *
     * <p>{@code HTTP GET} - Spring's default - is useless in a service that calls six dependencies:
     * every span in the trace has the same name. Leading with the client name makes the waterfall
     * readable at a glance.
     */
    @Override
    public String getContextualName(ClientRequestObservationContext context) {
        String method = context.getCarrier() == null ? "HTTP" : context.getCarrier().getMethod().name();
        return clientName + " " + method;
    }
}
