package ru.ludwigandreas.webcore.problem;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

/**
 * Base class for every failure a service reports to a client.
 *
 * <p>It carries a message <em>code</em> and its arguments rather than a formatted string: the text
 * is only resolved at the edge, against the caller's locale, so the same exception renders in
 * English or Russian without the service layer knowing a locale exists. {@code getMessage()} stays
 * developer-facing (logs, stack traces) and is never sent to a client.
 *
 * <p>Subclasses name the outcome and the code, and nothing else:
 *
 * <pre>{@code
 * public class ProductNotFoundException extends LocalizedException {
 *     public ProductNotFoundException(UUID id) {
 *         super(ProblemStatus.NOT_FOUND, "error.product.not-found", id);
 *     }
 * }
 * }</pre>
 *
 * <p>Anything a client needs to act on programmatically - which field, which limit, which id - goes
 * in {@link #getProperties()} rather than only into the sentence, because a caller cannot parse a
 * translated sentence. {@link #withProperty} is chainable at the throw site and returns {@code this}
 * typed, so a subclass constructor can attach its own context without re-declaring anything.
 */
@Getter
public abstract class LocalizedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final Object[] NO_ARGS = new Object[0];

    private final ProblemStatus status;
    private final String code;

    /**
     * Message-format arguments for {@link #getCode()}. Transient because a message argument is an
     * arbitrary domain object with no serialization contract; the code and the properties are what
     * carry meaning across a boundary.
     */
    private final transient Object[] args;

    private final transient Map<String, Object> properties = new LinkedHashMap<>();

    protected LocalizedException(ProblemStatus status, String code, Object... args) {
        this(status, code, null, args);
    }

    /**
     * @param cause the underlying failure. Kept for the log only - it is never rendered into the
     *              response, because an exception message from a driver or a parser is written for
     *              whoever operates this service, not for whoever called it.
     */
    protected LocalizedException(ProblemStatus status, String code, Throwable cause, Object... args) {
        super(code + " " + Arrays.toString(args), cause);
        this.status = status;
        this.code = code;
        this.args = args == null ? new Object[0] : args.clone();
    }

    /** Attaches one machine-readable property to the rendered problem. */
    @SuppressWarnings("unchecked")
    public <T extends LocalizedException> T withProperty(String name, Object value) {
        properties.put(name, value);
        return (T) this;
    }

    /**
     * Message-format arguments for {@link #getCode()}.
     *
     * <p>Empty rather than null on an instance that has been through Java serialization: the
     * arguments and the properties are transient, so what survives a round trip is the outcome and
     * the code - which is exactly the part that carries meaning across a boundary. Callers get an
     * exception that still renders, one sentence poorer, instead of a {@code NullPointerException}
     * while rendering an error.
     */
    public Object[] getArgs() {
        return args == null ? NO_ARGS : args.clone();
    }

    /** Machine-readable context, rendered as top-level members of the problem document. */
    public Map<String, Object> getProperties() {
        return properties == null ? Map.of() : Map.copyOf(properties);
    }

    /** This exception as a mapper-shaped definition, so one renderer serves both paths. */
    public ProblemDefinition toDefinition() {
        return new ProblemDefinition(status, code, getArgs(), getProperties());
    }
}
