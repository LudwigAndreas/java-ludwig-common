package ru.ludwigandreas.testsupport.local.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.testsupport.local.fixture.FixtureMode;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixtures;
import ru.ludwigandreas.testsupport.local.stub.StubFault;

/**
 * The ordering and parsing rules, which need no database to prove.
 *
 * <p>The fixture names are chosen against the tie-break: {@code Alpha} sorts first and declares that
 * it runs last, so an implementation that merely sorted by name would fail the first test.
 */
class SeedFixtureOrderTest {

    private static final List<String> RAN = new ArrayList<>();

    @Test
    @DisplayName("a fixture runs after everything it names, whatever its own name sorts as")
    void runsPredecessorsFirst() {
        RAN.clear();
        SeedFixtures fixtures = new SeedFixtures(List.of(new Alpha(), new Middle(), new Zulu()), null, null);

        assertThat(fixtures.names()).containsExactly("Zulu", "Middle", "Alpha");
        assertThat(fixtures.seed()).containsExactly("Zulu", "Middle", "Alpha");
        assertThat(RAN).containsExactly("Zulu", "Middle", "Alpha");
    }

    @Test
    @DisplayName("a cycle fails when the runner is created, naming its members")
    void cycleIsRefused() {
        assertThatThrownBy(() -> new SeedFixtures(List.of(new Chicken(), new Egg()), null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle")
                .hasMessageContaining("Chicken")
                .hasMessageContaining("Egg");
    }

    @Test
    @DisplayName("a predecessor that is not registered is named")
    void unregisteredPredecessorIsRefused() {
        assertThatThrownBy(() -> new SeedFixtures(List.of(new Middle()), null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Middle runs after Zulu, which is not registered");
    }

    @Test
    @DisplayName("one fixture type registered twice is refused")
    void duplicateIsRefused() {
        assertThatThrownBy(() -> new SeedFixtures(List.of(new Zulu(), new Zulu()), null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Zulu is registered more than once");
    }

    @Test
    @DisplayName("a failing fixture is named and nothing after it runs")
    void failureStopsTheRun() {
        RAN.clear();
        SeedFixtures fixtures = new SeedFixtures(List.of(new Zulu(), new Broken(), new AfterBroken()), null, null);

        assertThatThrownBy(fixtures::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Seed fixture Broken failed")
                .hasRootCauseMessage("no such supplier");
        assertThat(RAN).containsExactly("Zulu");
    }

    @Test
    @DisplayName("the fixtures that publish are the ones reported as needing Kafka")
    void reportsFixturesNeedingKafka() {
        SeedFixtures fixtures = new SeedFixtures(List.of(new Zulu(), new Publisher()), null, null);

        assertThat(fixtures.needingKafka()).containsExactly("Publisher");
    }

    @Test
    @DisplayName("the fixture mode defaults to reset and rejects an unknown value with the valid ones")
    void parsesFixtureMode() {
        assertThat(FixtureMode.parse(null)).isEqualTo(FixtureMode.RESET);
        assertThat(FixtureMode.parse(" Keep ")).isEqualTo(FixtureMode.KEEP);
        assertThatThrownBy(() -> FixtureMode.parse("wipe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reset, keep, none");
    }

    @Test
    @DisplayName("a scenario state maps back to its fault, and the start state to none")
    void mapsScenarioStates() {
        assertThat(StubFault.fromState("slow")).contains(StubFault.SLOW);
        assertThat(StubFault.fromState("Started")).isEmpty();
        assertThat(StubFault.fromState(null)).isEmpty();
    }

    private static class Recording implements SeedFixture {

        @Override
        public void seed() {
            RAN.add(getClass().getSimpleName());
        }
    }

    static class Zulu extends Recording {
    }

    static class Middle extends Recording {

        @Override
        public List<Class<? extends SeedFixture>> after() {
            return List.of(Zulu.class);
        }
    }

    static class Alpha extends Recording {

        @Override
        public List<Class<? extends SeedFixture>> after() {
            return List.of(Middle.class);
        }
    }

    static class Chicken extends Recording {

        @Override
        public List<Class<? extends SeedFixture>> after() {
            return List.of(Egg.class);
        }
    }

    static class Egg extends Recording {

        @Override
        public List<Class<? extends SeedFixture>> after() {
            return List.of(Chicken.class);
        }
    }

    static class Broken implements SeedFixture {

        @Override
        public void seed() {
            throw new IllegalArgumentException("no such supplier");
        }

        @Override
        public List<Class<? extends SeedFixture>> after() {
            return List.of(Zulu.class);
        }
    }

    static class AfterBroken extends Recording {

        @Override
        public List<Class<? extends SeedFixture>> after() {
            return List.of(Broken.class);
        }
    }

    static class Publisher extends Recording {

        @Override
        public boolean needsKafka() {
            return true;
        }
    }
}
