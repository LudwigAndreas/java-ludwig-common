package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.api.Fetcher;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Fetcher} stays four shapes.
 *
 * <p>An enumeration is a second task kind, not a fifth way to fetch known keys, and the engine's
 * walker has a branch for exactly these four. The count is asserted here rather than in ArchUnit
 * because permitted subclasses are a property of the sealed type itself, which a dependency rule
 * cannot count.
 */
class FetcherShapesTest {

    @Test
    void theSealedFetcherPermitsExactlyTheFourShapes() {
        assertThat(Arrays.stream(Fetcher.class.getPermittedSubclasses()).map(Class::getSimpleName))
                .containsExactlyInAnyOrder("PerItem", "Batched", "Paged", "JobFetcher");
    }
}
