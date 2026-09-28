package ru.ludwigandreas.restclient.core;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import org.springframework.http.client.observation.ClientRequestObservationContext;

/**
 * Recovers the URI <em>template</em> - {@code /invoices/{id}} - for the call currently being made.
 *
 * <h2>Why this is not simply a field somewhere</h2>
 *
 * <p>By the time a request reaches a {@code ClientHttpRequestFactory}, the template is gone: Spring
 * expanded it into a {@code URI} several layers up, and {@code HttpRequest} in this Spring version
 * carries no attributes to smuggle it down in.
 *
 * <p>It is recoverable because {@code DefaultRestClient} opens an {@link Observation} scope around
 * the exchange, and the observation's context is a {@link ClientRequestObservationContext} that was
 * given the template before the scope opened. Reading it from the current observation is therefore
 * exact, not a guess - and it costs one thread-local read.
 *
 * <h2>Why it matters enough to do this</h2>
 *
 * <p>The expanded path contains an identifier chosen by whatever data the service is processing.
 * Used as a metric tag it is unbounded cardinality - the failure
 * {@code UriCardinalityLimitingMeterFilter} exists to contain. Written into a retained audit record
 * it is an identifier in a compliance store. The template is neither, and it is what a human reads
 * anyway.
 *
 * <p>When no template is available - an ad-hoc call built from a concatenated string - the path is
 * used, and the cardinality filter is what keeps that from being a problem.
 */
public final class UriTemplates {

    private UriTemplates() {
    }

    /**
     * The template for the call in progress, falling back to {@code uri}'s path.
     *
     * <p>Two sources, in order. The current observation is exact and is what a service with metrics
     * or tracing has; {@link UriTemplateCapture} is the fallback for a service with neither, and is
     * exact for the {@code uri(template, vars)} forms. Only a URI assembled outside both - a
     * concatenated string - reaches the path fallback, and the metric cardinality filter is what
     * keeps that from being a problem.
     */
    public static String resolve(ObservationRegistry observationRegistry, URI uri) {
        String template = fromObservation(observationRegistry);
        if (template == null || template.isBlank()) {
            template = UriTemplateCapture.templateFor(uri);
        }
        if (template != null && !template.isBlank()) {
            return template;
        }
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static String fromObservation(ObservationRegistry observationRegistry) {
        if (observationRegistry == null) {
            return null;
        }
        Observation observation = observationRegistry.getCurrentObservation();
        if (observation == null) {
            return null;
        }
        Observation.Context context = observation.getContext();
        return context instanceof ClientRequestObservationContext clientContext
                ? clientContext.getUriTemplate()
                : null;
    }
}
