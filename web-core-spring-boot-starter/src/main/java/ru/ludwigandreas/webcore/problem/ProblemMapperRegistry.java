package ru.ludwigandreas.webcore.problem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

/**
 * The ordered chain of {@link ExceptionProblemMapper}s, and the cause-chain walk over it.
 *
 * <p>Mappers are consulted in {@code Ordered} order, first match wins, so precedence is a declared
 * number rather than a consequence of bean-discovery order.
 *
 * <p>It also walks the exception's causes, which matters more than it looks. The exception that
 * reaches an advice is frequently not the one that was thrown: a JPA flush wraps a constraint
 * violation, a transaction manager wraps a commit failure, a proxy wraps a checked exception. Trying
 * the whole chain means a module's mapper keeps working when the container decides to wrap its
 * exception, instead of that request silently degrading to a 500. Shallower causes are preferred, so
 * a wrapper that <em>is</em> mapped still wins over its own cause.
 */
public class ProblemMapperRegistry {

    /**
     * How far down the cause chain to look. Deep enough for the real wrapping cases (three or four
     * levels at worst) and bounded so a pathological chain cannot turn error rendering into the
     * slowest part of a request.
     */
    private static final int MAX_CAUSE_DEPTH = 10;

    private final List<ExceptionProblemMapper> mappers;

    public ProblemMapperRegistry(Collection<ExceptionProblemMapper> mappers) {
        List<ExceptionProblemMapper> sorted = new ArrayList<>(
                mappers == null ? List.of() : mappers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.mappers = List.copyOf(sorted);
    }

    /** The first definition any mapper produces for this exception or one of its causes. */
    public Optional<ProblemDefinition> resolve(Throwable exception) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = exception;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH && seen.add(current); depth++) {
            for (ExceptionProblemMapper mapper : mappers) {
                if (mapper.supports(current)) {
                    return Optional.of(mapper.map(current));
                }
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    /** The registered mappers in resolution order. Exposed for diagnostics and tests. */
    public List<ExceptionProblemMapper> mappers() {
        return mappers;
    }
}
