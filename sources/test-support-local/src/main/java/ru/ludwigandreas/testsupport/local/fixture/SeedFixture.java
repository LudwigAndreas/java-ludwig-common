package ru.ludwigandreas.testsupport.local.fixture;

import java.util.List;

/**
 * Data, or messages, a service needs present before it can be exercised locally.
 *
 * <h2>A fixture is code, not a SQL script</h2>
 *
 * <p>An implementation writes through the service's own repositories or services, which it receives
 * by constructor injection like any other bean of the launched application. A SQL seed file keeps
 * inserting a column that was renamed last month and nobody finds out until the data looks wrong; a
 * fixture written against the entity stops compiling, or fails at the next launch.
 *
 * <p>No check enforces that, and none is possible: nothing distinguishes a seed script from any other
 * test resource a service may legitimately have, so a rule against one would be a rule against all of
 * them. It is recorded here, at the point of the rule, and in the module README.
 *
 * <h2>Registering one</h2>
 *
 * <p>Leave the class without a stereotype annotation. A {@code @Component} under the service's base
 * package is picked up by every {@code @SpringBootTest} the service has, whether that test wants
 * seed data or not. Name the fixtures explicitly instead - on {@code LocalRun.fixtures(...)} for the
 * local application, and in an {@code @Import} beside {@link SeedFixtureConfiguration} for a test.
 *
 * <h2>Each fixture is one transaction</h2>
 *
 * <p>When the application has a transaction manager, {@link #seed()} runs inside a transaction of its
 * own: a fixture that throws leaves nothing of itself behind, and no later fixture runs.
 */
public interface SeedFixture {

    /** Writes this fixture's data. */
    void seed();

    /**
     * The fixtures whose data this one needs to exist first.
     *
     * @return fixture types that run before this one; a cycle fails start-up naming its members
     */
    default List<Class<? extends SeedFixture>> after() {
        return List.of();
    }

    /**
     * Whether this fixture publishes messages.
     *
     * @return {@code true} when the fixture needs a broker; the local application then refuses to
     *         start unless it named Kafka, instead of failing later on a connection timeout
     */
    default boolean needsKafka() {
        return false;
    }
}
