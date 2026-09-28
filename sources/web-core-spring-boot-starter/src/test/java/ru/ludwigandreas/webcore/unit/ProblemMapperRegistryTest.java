package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The registry decides which module's opinion about a failure wins, and whether a wrapped exception
 * is still recognised. Both are the kind of thing that works by accident until a second module is
 * installed, so they are pinned explicitly.
 */
class ProblemMapperRegistryTest {

    @Test
    @DisplayName("the lowest-order mapper wins, whatever order the beans were given in")
    void lowestOrderWins() {
        ExceptionProblemMapper late = mapper(IllegalStateException.class, "late", 100);
        ExceptionProblemMapper early = mapper(IllegalStateException.class, "early", 10);
        ProblemMapperRegistry registry = new ProblemMapperRegistry(List.of(late, early));

        assertThat(registry.resolve(new IllegalStateException()))
                .get()
                .extracting(ProblemDefinition::code)
                .isEqualTo("early");
    }

    @Test
    @DisplayName("an exception no mapper claims resolves to nothing, so the advice can answer 500")
    void unmappedResolvesToEmpty() {
        ProblemMapperRegistry registry =
                new ProblemMapperRegistry(List.of(mapper(IllegalStateException.class, "state", 0)));

        assertThat(registry.resolve(new UnsupportedOperationException())).isEmpty();
    }

    @Test
    @DisplayName("a mapped exception wrapped by a framework layer is still recognised")
    void walksTheCauseChain() {
        // The real case this exists for: a business exception wrapped by a transaction manager or a
        // proxy, which would otherwise degrade from a deliberate 409 to an accidental 500.
        ProblemMapperRegistry registry =
                new ProblemMapperRegistry(List.of(mapper(IllegalStateException.class, "state", 0)));

        Throwable wrapped = new RuntimeException("wrapper", new IllegalStateException("real cause"));

        assertThat(registry.resolve(wrapped)).get().extracting(ProblemDefinition::code).isEqualTo("state");
    }

    @Test
    @DisplayName("a wrapper that is itself mapped wins over its mapped cause")
    void prefersTheShallowestMatch() {
        ProblemMapperRegistry registry = new ProblemMapperRegistry(List.of(
                mapper(IllegalArgumentException.class, "wrapper", 0),
                mapper(IllegalStateException.class, "cause", 0)));

        Throwable wrapped = new IllegalArgumentException("outer", new IllegalStateException("inner"));

        assertThat(registry.resolve(wrapped)).get().extracting(ProblemDefinition::code).isEqualTo("wrapper");
    }

    @Test
    @DisplayName("a self-referencing cause chain terminates instead of spinning")
    void survivesCyclicCauseChain() {
        // Rare but real: some libraries re-set a cause to an ancestor when re-throwing.
        Throwable first = new RuntimeException("first");
        Throwable second = new RuntimeException("second", first);
        first.initCause(second);
        ProblemMapperRegistry registry =
                new ProblemMapperRegistry(List.of(mapper(IllegalStateException.class, "state", 0)));

        assertThat(registry.resolve(first)).isEmpty();
    }

    @Test
    @DisplayName("a cause deeper than the walk's limit is not searched for")
    void stopsAtTheDepthLimit() {
        Throwable deep = new IllegalStateException("target");
        for (int i = 0; i < 12; i++) {
            deep = new RuntimeException("wrapper " + i, deep);
        }
        ProblemMapperRegistry registry =
                new ProblemMapperRegistry(List.of(mapper(IllegalStateException.class, "state", 0)));

        assertThat(registry.resolve(deep)).isEmpty();
    }

    @Test
    @DisplayName("forType matches subtypes, which is what a module contributing a base type relies on")
    void forTypeMatchesSubtypes() {
        ExceptionProblemMapper mapper = ExceptionProblemMapper.forType(
                RuntimeException.class,
                e -> ProblemDefinition.of(ProblemStatus.INVALID, "runtime"));

        assertThat(mapper.supports(new IllegalStateException())).isTrue();
        assertThat(mapper.supports(new Exception())).isFalse();
    }

    private static ExceptionProblemMapper mapper(Class<? extends Throwable> type, String code, int order) {
        return ExceptionProblemMapper.forType(
                type, e -> ProblemDefinition.of(ProblemStatus.CONFLICT, code), order);
    }
}
