package ru.ludwigandreas.security.exception;

import java.util.Arrays;
import java.util.Map;

/**
 * A refusal the security starter raises on every surface, carrying what a problem response needs - its code,
 * message arguments and problem properties - without naming any web-core type, so it can be raised in a service
 * that has no problem pipeline.
 *
 * <p>The status is not here: it is web-core's type, so {@code SecurityProblemMapper}, which exists only where
 * web-core does, decides it per subclass.
 */
public abstract class SecurityRefusalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    private final transient Object[] args;

    private final transient Map<String, Object> properties;

    protected SecurityRefusalException(String code, Map<String, Object> properties, Object... args) {
        super(code + " " + Arrays.toString(args));
        this.code = code;
        this.args = args.clone();
        this.properties = Map.copyOf(properties);
    }

    /** The problem code, also the message key. */
    public String code() {
        return code;
    }

    /** The message arguments. */
    public Object[] args() {
        return args.clone();
    }

    /** The extra members of the problem body. */
    public Map<String, Object> properties() {
        return properties;
    }
}
