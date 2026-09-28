package ru.ludwigandreas.restclient.spi;

/**
 * Maps a failed response onto an exception this service's own code understands.
 *
 * <p>The starter ships one translator that reads RFC 9457 / RFC 7807 {@code application/problem+json}
 * and carries its {@code type}, {@code title}, {@code detail} and any extension members through. That
 * covers every partner that follows the standard, and no partner that does not - which is why this
 * is an SPI. A translator is a Spring bean; publishing one is how a service maps
 * {@code {"errorCode":"E42","msg":"..."} } onto the platform's error model without this starter
 * learning about that partner.
 *
 * <p>Translators are consulted in {@code @Order} order and the first non-null answer wins; the
 * built-in one runs last, so a service's translator always gets first refusal. Returning {@code null}
 * means "not mine".
 */
public interface ResponseErrorTranslator {

    /** Whether this translator applies to {@code clientName}. Defaults to every client. */
    default boolean supports(String clientName) {
        return true;
    }

    /**
     * The exception to throw for this response, or {@code null} to defer to the next translator.
     *
     * <p>Returning an exception that is not a {@code RestClientException} is allowed and sometimes
     * right - mapping a partner's 409 onto the service's own {@code DuplicateOrderException} is the
     * point of this SPI. The client name and the correlation id are in {@link ErrorContext} so that
     * a translated exception can still carry them.
     */
    RuntimeException translate(ErrorContext context);
}
