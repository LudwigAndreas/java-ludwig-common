package ru.ludwigandreas.restclient.error;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import ru.ludwigandreas.restclient.core.AnnotatedClientHttpResponse;
import ru.ludwigandreas.restclient.core.CallContextSource;
import ru.ludwigandreas.restclient.observability.HeaderRedactor;
import ru.ludwigandreas.restclient.spi.ErrorContext;

/**
 * Turns a failed response into this platform's typed exception, for a {@code sync} client.
 *
 * <p>Registered as the client's {@code defaultStatusHandler}, which means it applies to
 * {@code retrieve()} and deliberately not to {@code exchange()}: a caller who asked for the raw
 * exchange wants the raw response, including a 404 it intends to treat as an empty result.
 *
 * <p>It replaces Spring's {@code DefaultResponseErrorHandler} entirely. That handler throws
 * {@code HttpServerErrorException}, which does not know which dependency produced it - so a service
 * calling six of them cannot tell, from the exception alone, which one is down.
 */
public class TranslatingResponseErrorHandler implements ResponseErrorHandler {

    private static final int FIRST_ERROR_STATUS = 400;

    private final String clientName;
    private final ResponseErrorTranslation translation;
    private final HeaderRedactor redactor;
    private final CallContextSource callContext;
    private final int maxBodySize;

    /** Creates the handler for one named client. */
    // CHECKSTYLE.OFF: ParameterNumber - five per-client collaborators, all needed to build one
    // ErrorContext.
    public TranslatingResponseErrorHandler(String clientName, ResponseErrorTranslation translation,
                                           HeaderRedactor redactor, CallContextSource callContext,
                                           int maxBodySize) {
        this.clientName = clientName;
        this.translation = translation;
        this.redactor = redactor;
        this.callContext = callContext;
        this.maxBodySize = maxBodySize;
    }
    // CHECKSTYLE.ON: ParameterNumber

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().value() >= FIRST_ERROR_STATUS;
    }

    @Override
    public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
        throw translation.translate(context(url, method, response));
    }

    /** Never used, but part of the interface; delegates to the three-argument form's logic. */
    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        throw translation.translate(new ErrorContext(clientName, "UNKNOWN", URI.create("/"), "/",
                response.getStatusCode().value(), response.getStatusText(),
                mediaType(response.getHeaders()), redactor.redact(response.getHeaders()),
                body(response), callContext.correlationId()));
    }

    private ErrorContext context(URI url, HttpMethod method, ClientHttpResponse response)
            throws IOException {
        String uriTemplate = response instanceof AnnotatedClientHttpResponse annotated
                ? annotated.getUriTemplate()
                : url.getRawPath();
        String body = body(response);
        MediaType contentType = response.getHeaders().getContentType();
        return new ErrorContext(
                clientName,
                method.name(),
                url,
                uriTemplate,
                response.getStatusCode().value(),
                response.getStatusText(),
                contentType == null ? null : contentType.toString().toLowerCase(java.util.Locale.ROOT),
                redactor.redact(response.getHeaders()),
                body,
                callContext.correlationId());
    }

    /**
     * The body, preferring the snippet the pipeline already redacted.
     *
     * <p>Reading it again here would be a second consumption of a stream the pipeline may already
     * have drained, and would skip redaction - an error body from an authentication endpoint
     * routinely echoes back the credential that was rejected.
     */
    private String body(ClientHttpResponse response) throws IOException {
        if (response instanceof AnnotatedClientHttpResponse annotated
                && annotated.getBodySnippet() != null) {
            return annotated.getBodySnippet();
        }
        byte[] raw = response.getBody().readNBytes(maxBodySize);
        if (raw.length == 0) {
            return null;
        }
        MediaType contentType = response.getHeaders().getContentType();
        Charset charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset() : StandardCharsets.UTF_8;
        return new String(raw, charset);
    }

    private String mediaType(HttpHeaders headers) {
        MediaType contentType = headers.getContentType();
        return contentType == null ? null : contentType.toString().toLowerCase(java.util.Locale.ROOT);
    }
}
