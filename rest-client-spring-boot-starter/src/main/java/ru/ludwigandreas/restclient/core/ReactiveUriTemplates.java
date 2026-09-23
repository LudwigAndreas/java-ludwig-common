package ru.ludwigandreas.restclient.core;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Recovers the URI template for a reactive call.
 *
 * <p>Easier than its blocking counterpart: {@code WebClient} stores the template as a request
 * attribute, precisely so that instrumentation can find it. Reading the attribute is exact, and the
 * fallback to the raw path only happens for a call built from a fully formed {@code URI}, where no
 * template ever existed.
 *
 * <p>The attribute name is the one {@code DefaultWebClient} publishes. It is referenced by value
 * rather than by constant because the constant is package-private in Spring - which is also why this
 * class exists at all rather than the expression being inlined at the call site: when Spring changes
 * it, there is exactly one place to fix and one test that fails.
 */
public final class ReactiveUriTemplates {

    private static final String URI_TEMPLATE_ATTRIBUTE = WebClient.class.getName() + ".uriTemplate";

    private ReactiveUriTemplates() {
    }

    /** The template for {@code request}, falling back to its raw path. */
    public static String resolve(ClientRequest request) {
        Object template = request.attributes().get(URI_TEMPLATE_ATTRIBUTE);
        if (template instanceof String value && !value.isBlank()) {
            return value;
        }
        String path = request.url().getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }
}
