package ru.ludwigandreas.messaging.error;

/**
 * Base class for this module's failures.
 *
 * <p>Deliberately <em>not</em> a {@code LocalizedException}. That base class belongs to web-core's
 * RFC 9457 pipeline and is for a failure a client is waiting on; everything this module raises happens
 * on a listener thread, where there is no caller, no locale and no response - the destination is a
 * dead-letter topic and a log line. Extending it here would also make web-core a non-optional
 * dependency of this module and therefore of every {@code outbox-spring-boot-starter} consumer, for the
 * benefit of a response nobody receives.
 *
 * <p>The localized rendering still exists, for the service that chooses to re-raise one of these from
 * its own HTTP surface: {@code MessagingWebAutoConfiguration} contributes an
 * {@code ExceptionProblemMapper} and the bundle, so the mapping lives at the one boundary that has a
 * locale, and the exception itself stays free of the web layer. That is the same extension point
 * web-core documents as the replacement for per-module exception advice.
 */
public abstract class MessagingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The message-bundle key for this failure, published so the mapper need not switch on the type. */
    private final String code;

    protected MessagingException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * The message-bundle key.
     *
     * @return the code
     */
    public String getCode() {
        return code;
    }
}
