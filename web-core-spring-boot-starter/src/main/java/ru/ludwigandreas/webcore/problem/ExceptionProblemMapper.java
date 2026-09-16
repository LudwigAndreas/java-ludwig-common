package ru.ludwigandreas.webcore.problem;

import java.util.function.Function;
import org.springframework.core.Ordered;

/**
 * Teaches the shared advice how to render an exception it does not own.
 *
 * <p>This is the extension point that replaces per-module exception advice. A module used to ship
 * its own {@code @RestControllerAdvice} for its own exception types, which had two costs: its
 * messages could not be localized without that module also owning a message bundle and a
 * {@code MessageSource} lookup, and an application that wanted one consistent error shape had to
 * switch the advice off and re-handle those exceptions itself. Contributing a mapper instead means
 * the module states only the <em>meaning</em> of its failure - this is a 400, this is its code, this
 * is the field at fault - and the text comes from the bundle it contributes alongside it, in the
 * caller's language, rendered identically to every other error the service emits.
 *
 * <p>Mappers are consulted in {@link Ordered} order and the first match wins, so an application can
 * override a module's mapping by registering its own at a lower order. A module's own mapper should
 * therefore sit at a high order value - see {@link #DEFAULT_MODULE_ORDER}.
 */
public interface ExceptionProblemMapper extends Ordered {

    /**
     * The order a library's own mappers should register at: late enough that an application's
     * mapper, registered at the default order of 0, takes precedence without having to know what
     * number the library picked.
     */
    int DEFAULT_MODULE_ORDER = 1000;

    boolean supports(Throwable exception);

    /** Called only when {@link #supports(Throwable)} returned {@code true} for this exception. */
    ProblemDefinition map(Throwable exception);

    @Override
    default int getOrder() {
        return 0;
    }

    /**
     * A mapper for one exception type and its subtypes, which is what almost every contribution
     * needs:
     *
     * <pre>{@code
     * ExceptionProblemMapper.forType(
     *         PageSizeExceededException.class,
     *         e -> ProblemDefinition.of(ProblemStatus.INVALID, "ludwig.odata.error.page-size")
     *                 .withProperty("maxPageSize", e.max()));
     * }</pre>
     */
    static <E extends Throwable> ExceptionProblemMapper forType(
            Class<E> type, Function<E, ProblemDefinition> mapping) {
        return forType(type, mapping, DEFAULT_MODULE_ORDER);
    }

    static <E extends Throwable> ExceptionProblemMapper forType(
            Class<E> type, Function<E, ProblemDefinition> mapping, int order) {
        if (type == null || mapping == null) {
            throw new IllegalArgumentException("Both an exception type and a mapping are required");
        }
        return new ExceptionProblemMapper() {

            @Override
            public boolean supports(Throwable exception) {
                return type.isInstance(exception);
            }

            @Override
            public ProblemDefinition map(Throwable exception) {
                return mapping.apply(type.cast(exception));
            }

            @Override
            public int getOrder() {
                return order;
            }

            @Override
            public String toString() {
                return "ExceptionProblemMapper(" + type.getName() + ", order=" + order + ")";
            }
        };
    }
}
