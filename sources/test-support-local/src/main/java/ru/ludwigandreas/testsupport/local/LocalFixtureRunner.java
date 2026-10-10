package ru.ludwigandreas.testsupport.local;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import ru.ludwigandreas.testsupport.local.fixture.FixtureMode;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixtures;

/**
 * Seeds the local application once the context is up, which is after its migrations have run.
 *
 * <p>An application runner and not a bean initializer on purpose: by the time a runner is called
 * every repository exists and Liquibase has finished, and an exception here still fails start-up.
 * It runs first among the runners so the summary, which runs last, can say what was seeded.
 */
final class LocalFixtureRunner implements ApplicationRunner, Ordered {

    private final LocalRunPlan plan;
    private final SeedFixtures fixtures;
    private final Environment environment;

    private FixtureMode mode = FixtureMode.RESET;
    private List<String> ran = List.of();

    LocalFixtureRunner(LocalRunPlan plan, SeedFixtures fixtures, Environment environment) {
        this.plan = plan;
        this.fixtures = fixtures;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        mode = FixtureMode.parse(environment.getProperty(FixtureMode.PROPERTY));
        if (mode == FixtureMode.NONE) {
            return;
        }
        List<String> publishing = fixtures.needingKafka();
        if (!publishing.isEmpty() && !plan.services().contains(BackingService.KAFKA)) {
            throw new IllegalStateException("Seed fixtures " + publishing + " need Kafka to publish "
                    + "to, but the local application did not name it. Add .kafka() to the LocalRun, or "
                    + "set " + FixtureMode.PROPERTY + "=none.");
        }
        ran = fixtures.run(mode);
    }

    FixtureMode mode() {
        return mode;
    }

    List<String> ran() {
        return ran;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
