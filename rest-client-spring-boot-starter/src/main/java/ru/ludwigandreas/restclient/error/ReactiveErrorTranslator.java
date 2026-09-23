package ru.ludwigandreas.restclient.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import ru.ludwigandreas.restclient.core.CallContextSource;
import ru.ludwigandreas.restclient.observability.HeaderRedactor;
import ru.ludwigandreas.restclient.spi.ErrorContext;

/**
 * The {@code async} counterpart of {@link TranslatingResponseErrorHandler}: the same translator
 * chain, the same exceptions, reached through {@code WebClient}'s {@code defaultStatusHandler}.
 *
 * <p>One difference is worth stating rather than hiding: the URI template is not available here.
 * {@code ClientResponse} exposes the request's URL but not its attributes, which is where
 * {@code WebClient} keeps the template, so a reactive exception quotes the request path. The metric
 * and the span are unaffected - both are produced from the observation, which does have the
 * template - so this shows up only in the exception message.
 */
public class ReactiveErrorTranslator {

    private static final int FIRST_ERROR_STATUS = 400;

    private final String clientName;
    private final ResponseErrorTranslation translation;
    private final HeaderRedactor redactor;
    private final CallContextSource callContext;
    private final int maxBodySize;

    /** Creates the translator for one named client. */
    // CHECKSTYLE.OFF: ParameterNumber - mirrors the blocking handler, for the same reasons.
    public ReactiveErrorTranslator(String clientName, ResponseErrorTranslation translation,
                                   HeaderRedactor redactor, CallContextSource callContext,
                                   int maxBodySize) {
        this.clientName = clientName;
        this.translation = translation;
        this.redactor = redactor;
        this.callContext = callContext;
        this.maxBodySize = maxBodySize;
    }
    // CHECKSTYLE.ON: ParameterNumber

    /** Whether {@code status} is one this translator turns into an exception. */
    public boolean isError(org.springframework.http.HttpStatusCode status) {
        return status.value() >= FIRST_ERROR_STATUS;
    }

    /** The exception for {@code response}, as a {@code Mono} because reading the body is async. */
    public Mono<Throwable> translate(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> (Throwable) translation.translate(context(response, body)))
                // An error while reading the error body must not replace the error: the status is
                // already known and is the more useful half of the message.
                .onErrorResume(failure -> Mono.just(translation.translate(context(response, null))));
    }

    private ErrorContext context(ClientResponse response, String body) {
        MediaType contentType = response.headers().contentType().orElse(null);
        URI url = response.request() == null ? URI.create("/") : response.request().getURI();
        String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
        return new ErrorContext(
                clientName,
                response.request() == null ? "UNKNOWN" : response.request().getMethod().name(),
                url,
                path,
                response.statusCode().value(),
                null,
                contentType == null ? null : contentType.toString().toLowerCase(Locale.ROOT),
                redactor.redact(response.headers().asHttpHeaders()),
                redactor.redactBody(body, contentType == null ? null : contentType.toString(), maxBodySize),
                callContext.correlationId());
    }
}
