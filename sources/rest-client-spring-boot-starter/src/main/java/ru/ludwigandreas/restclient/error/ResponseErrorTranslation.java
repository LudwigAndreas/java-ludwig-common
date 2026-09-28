package ru.ludwigandreas.restclient.error;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.restclient.spi.ErrorContext;
import ru.ludwigandreas.restclient.spi.ResponseErrorTranslator;

/**
 * Runs the {@link ResponseErrorTranslator} chain and guarantees an exception comes out.
 *
 * <p>The guarantee is the point. Every caller of this class is on a failure path already, and a
 * translator that returns {@code null} for a response nobody anticipated, or that throws while
 * building its exception, must not turn "the partner returned 503" into a
 * {@code NullPointerException} in this starter. Both cases fall through to the built-in translator,
 * and its failure would be a bug in this module rather than in a service's extension.
 */
public class ResponseErrorTranslation {

    private static final Logger log = LoggerFactory.getLogger(ResponseErrorTranslation.class);

    private final List<ResponseErrorTranslator> translators;
    private final ProblemDetailResponseErrorTranslator fallback;

    /**
     * Creates the chain, with a built-in translator that always produces an exception.
     *
     * @param translators ordered chain; the built-in translator is expected to be last, but this
     *                    class does not rely on that - {@code fallback} is used whatever the chain
     *                    does
     */
    public ResponseErrorTranslation(List<ResponseErrorTranslator> translators,
                                    ProblemDetailResponseErrorTranslator fallback) {
        this.translators = List.copyOf(translators);
        this.fallback = fallback;
    }

    /** The exception for a failed response. Never {@code null}. */
    public RuntimeException translate(ErrorContext context) {
        for (ResponseErrorTranslator translator : translators) {
            if (!translator.supports(context.clientName())) {
                continue;
            }
            RuntimeException translated = invoke(translator, context);
            if (translated != null) {
                return translated;
            }
        }
        return fallback.translate(context);
    }

    private RuntimeException invoke(ResponseErrorTranslator translator, ErrorContext context) {
        try {
            return translator.translate(context);
        } catch (RuntimeException ex) {
            // A translator is service-supplied code running on the failure path. Letting it replace
            // the upstream failure with its own would hide the incident behind a bug in the mapping
            // of the incident.
            log.warn("ResponseErrorTranslator {} failed for client {}; falling back to the built-in "
                            + "translation", translator.getClass().getName(), context.clientName(), ex);
            return null;
        }
    }
}
