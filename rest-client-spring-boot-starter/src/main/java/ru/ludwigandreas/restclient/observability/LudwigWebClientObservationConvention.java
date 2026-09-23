package ru.ludwigandreas.restclient.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;

/**
 * The {@code async} counterpart of {@link LudwigClientRequestObservationConvention}: same metric
 * name, same {@code client} tag, same span naming.
 *
 * <p>A separate class because Spring's blocking and reactive client observations have separate
 * context types in separate packages with no common supertype. The duplication is four lines and is
 * preferable to a reflective bridge, which would move the failure from compile time to the first
 * reactive call.
 */
public class LudwigWebClientObservationConvention extends DefaultClientRequestObservationConvention {

    private final String clientName;

    public LudwigWebClientObservationConvention(String clientName) {
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

    @Override
    public String getContextualName(ClientRequestObservationContext context) {
        String method = context.getRequest() == null ? "HTTP" : context.getRequest().method().name();
        return clientName + " " + method;
    }
}
