package ru.ludwigandreas.webcore.problem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a failure should be rendered as, before anything has been localized or turned into HTTP.
 *
 * <p>This is the single currency of the pipeline. A {@link LocalizedException} converts itself into
 * one; an {@link ExceptionProblemMapper} produces one for a type it did not author. Because both
 * paths end in the same record, {@code ProblemDetailFactory} is the only place that knows how a
 * problem document is shaped - which is what lets a new module add errors to an API without adding
 * another {@code @RestControllerAdvice} that renders them slightly differently.
 *
 * @param status     the outcome, which fixes the HTTP status
 * @param code       the message-bundle key; also published as the problem's {@code code} member, so
 *                   it is part of the API contract and a client may branch on it
 * @param args       message-format arguments for {@code code}
 * @param properties machine-readable members added to the problem document
 */
public record ProblemDefinition(
        ProblemStatus status,
        String code,
        Object[] args,
        Map<String, Object> properties) {

    private static final Object[] NO_ARGS = new Object[0];

    public ProblemDefinition {
        if (status == null) {
            throw new IllegalArgumentException("ProblemDefinition.status is required");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("ProblemDefinition.code is required");
        }
        args = args == null ? NO_ARGS : args.clone();
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static ProblemDefinition of(ProblemStatus status, String code, Object... args) {
        return new ProblemDefinition(status, code, args, Map.of());
    }

    /** Returns a copy with one more machine-readable member. */
    public ProblemDefinition withProperty(String name, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(properties);
        merged.put(name, value);
        return new ProblemDefinition(status, code, args, merged);
    }

    @Override
    public Object[] args() {
        return args.clone();
    }
}
