package ru.ludwigandreas.restclient.core;

import java.net.URI;
import java.util.Map;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriBuilderFactory;

/**
 * A {@code UriBuilderFactory} that notes the template it expanded before handing back the URI.
 *
 * <p>It delegates every decision to {@link DefaultUriBuilderFactory} - encoding mode, base URL
 * resolution, variable expansion - and adds one side effect. That matters: URI encoding is subtle
 * enough that a re-implementation would eventually differ from Spring's in some corner, and the
 * corner would be a partner rejecting a perfectly valid path.
 *
 * <p>The {@link #uriString(String)} form is <em>not</em> captured. Recording a template there means
 * recording it before the URI it produces exists, so the exactness check in
 * {@link UriTemplateCapture} could not be applied, and a stale entry would be indistinguishable from
 * a fresh one. Calls made that way fall back to the observation - which is present in any service
 * with metrics or tracing - and to the request path otherwise.
 */
public class TemplateCapturingUriBuilderFactory implements UriBuilderFactory {

    private final DefaultUriBuilderFactory delegate;
    /** Creates a factory resolving against {@code baseUrl}, or against nothing when it is absent. */
    public TemplateCapturingUriBuilderFactory(String baseUrl) {
        this.delegate = baseUrl == null || baseUrl.isBlank()
                ? new DefaultUriBuilderFactory()
                : new DefaultUriBuilderFactory(baseUrl);
    }

    @Override
    public URI expand(String uriTemplate, Map<String, ?> uriVariables) {
        URI uri = delegate.expand(uriTemplate, uriVariables);
        UriTemplateCapture.record(uriTemplate, uri);
        return uri;
    }

    @Override
    public URI expand(String uriTemplate, Object... uriVariables) {
        URI uri = delegate.expand(uriTemplate, uriVariables);
        UriTemplateCapture.record(uriTemplate, uri);
        return uri;
    }

    @Override
    public UriBuilder uriString(String uriTemplate) {
        return delegate.uriString(uriTemplate);
    }

    @Override
    public UriBuilder builder() {
        return delegate.builder();
    }
}
