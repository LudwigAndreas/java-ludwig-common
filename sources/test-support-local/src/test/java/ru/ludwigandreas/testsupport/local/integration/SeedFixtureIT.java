package ru.ludwigandreas.testsupport.local.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ludwigandreas.testsupport.data.DatabaseCleaner;
import ru.ludwigandreas.testsupport.local.LocalRun;
import ru.ludwigandreas.testsupport.local.fixture.FixtureMode;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixtureConfiguration;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixtures;
import ru.ludwigandreas.testsupport.local.integration.sample.AnnotationSeed;
import ru.ludwigandreas.testsupport.local.integration.sample.AnnouncementSeed;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleApplication;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleFixtures;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleStubs;
import ru.ludwigandreas.testsupport.local.integration.sample.Widget;
import ru.ludwigandreas.testsupport.local.integration.sample.WidgetRepository;
import ru.ludwigandreas.testsupport.local.integration.sample.WidgetSeed;

/**
 * Proves the seed fixtures against a real database: order, the three modes, and what a failure leaves.
 *
 * <p>The class is itself the "fixtures are usable from an integration test" scenario - it imports
 * {@link SeedFixtureConfiguration} beside the sample's fixtures and injects the runner, with no
 * launcher involved.
 */
@SampleContext
@Import({SeedFixtureConfiguration.class, SampleFixtures.class})
class SeedFixtureIT {

    private static final Widget ALPHA = new Widget("alpha", AnnotationSeed.NOTE);
    private static final Widget BETA = new Widget("beta", AnnotationSeed.NOTE);

    @Autowired
    private SeedFixtures fixtures;

    @Autowired
    private WidgetRepository widgets;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void emptyDatabase() {
        new DatabaseCleaner(dataSource).clean();
    }

    @Test
    @DisplayName("a test seeds on demand and gets the rows, with the dependent fixture run second")
    void seedsOnDemandInDeclaredOrder() {
        assertThat(fixtures.reset()).containsExactly("WidgetSeed", "AnnotationSeed");

        // The notes are only filled when the widgets existed first; the names alone sort the other way.
        assertThat(widgets.findAll()).containsExactly(ALPHA, BETA);
    }

    @Test
    @DisplayName("reset: a previous run's rows are removed and the fixtures run once")
    void resetStartsFromTheSameState() {
        widgets.save(new Widget("left-over", "from yesterday"));

        assertThat(fixtures.run(FixtureMode.RESET)).containsExactly("WidgetSeed", "AnnotationSeed");

        assertThat(widgets.findAll()).containsExactly(ALPHA, BETA);
    }

    @Test
    @DisplayName("keep: a database a previous run seeded and the developer changed is left alone")
    void keepLeavesExistingDataAlone() {
        fixtures.reset();
        Widget mine = new Widget("mine", "do not touch");
        widgets.save(mine);

        assertThat(fixtures.run(FixtureMode.KEEP)).isEmpty();

        assertThat(widgets.findAll()).containsExactly(ALPHA, BETA, mine);
    }

    @Test
    @DisplayName("keep: an empty database is seeded")
    void keepSeedsAnEmptyDatabase() {
        assertThat(fixtures.run(FixtureMode.KEEP)).containsExactly("WidgetSeed", "AnnotationSeed");

        assertThat(widgets.findAll()).containsExactly(ALPHA, BETA);
    }

    @Test
    @DisplayName("none: nothing is emptied and nothing is seeded")
    void noneDoesNothing() {
        Widget mine = new Widget("mine", null);
        widgets.save(mine);

        assertThat(fixtures.run(FixtureMode.NONE)).isEmpty();

        assertThat(widgets.findAll()).containsExactly(mine);
    }

    @Test
    @DisplayName("a failing fixture's own writes are rolled back and no later fixture runs")
    void failureRollsBackItsOwnWrites() {
        SeedFixtures withFailure = new SeedFixtures(
                List.of(new WidgetSeed(widgets), new DoomedSeed(widgets), new NeverSeed(widgets)),
                dataSource, transactionManager);

        assertThatThrownBy(withFailure::reset)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Seed fixture DoomedSeed failed");

        // What ran before it stays; its own row and the later fixture's row do not exist.
        assertThat(widgets.findAll()).extracting(Widget::code).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("a fixture that fails stops the local application from starting, by name")
    void failureStopsTheLaunch() {
        assertThatThrownBy(() -> LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .fixtures(WidgetSeed.class, DoomedSeed.class)
                .run())
                .hasStackTraceContaining("Seed fixture DoomedSeed failed");
    }

    @Test
    @DisplayName("a fixture that publishes stops the launch when Kafka was not named")
    void publishingFixtureNeedsKafkaNamed() {
        assertThatThrownBy(() -> LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .fixtures(AnnouncementSeed.class)
                .run())
                .hasStackTraceContaining("Seed fixtures [AnnouncementSeed] need Kafka");
    }

    @Test
    @DisplayName("the launcher's default mode empties a reused database before seeding")
    void launcherResetsByDefault() {
        widgets.save(new Widget("left-over", "from yesterday"));

        try (ConfigurableApplicationContext context = LocalRun.from(SampleApplication::main)
                .postgres()
                .stubs(new SampleStubs())
                .fixtures(SampleFixtures.class)
                .run()) {
            assertThat(context.getBean(WidgetRepository.class).findAll()).containsExactly(ALPHA, BETA);
        }
    }

    /** Writes a row and then fails, to show the row does not survive. */
    static class DoomedSeed implements SeedFixture {

        private final WidgetRepository widgets;

        DoomedSeed(WidgetRepository widgets) {
            this.widgets = widgets;
        }

        @Override
        public void seed() {
            widgets.save(new Widget("doomed", null));
            throw new IllegalStateException("the partner record this widget needs is missing");
        }

        @Override
        public List<Class<? extends SeedFixture>> after() {
            return List.of(WidgetSeed.class);
        }
    }

    /** Runs after the failing fixture, which is to say never. */
    static class NeverSeed implements SeedFixture {

        private final WidgetRepository widgets;

        NeverSeed(WidgetRepository widgets) {
            this.widgets = widgets;
        }

        @Override
        public void seed() {
            widgets.save(new Widget("never", null));
        }

        @Override
        public List<Class<? extends SeedFixture>> after() {
            return List.of(DoomedSeed.class);
        }
    }
}
