package ru.ludwigandreas.testsupport.local.fixture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;
import ru.ludwigandreas.testsupport.data.DatabaseCleaner;

/**
 * Runs a service's seed fixtures in an order that satisfies what each says it runs after.
 *
 * <p>The local application calls {@link #run(FixtureMode)} once at start-up. An integration test
 * injects this and calls {@link #reset()} from a {@code @BeforeEach}, which gives it the rows the
 * local application starts with.
 *
 * <p>The order is fixed when this object is created, so a cycle or an unregistered predecessor fails
 * the application's start-up rather than the first run.
 */
public final class SeedFixtures {

    private static final String SCHEMA = "public";
    private static final String MIGRATION_TABLES = "databasechangelog%";

    private final List<SeedFixture> ordered;
    private final DataSource dataSource;
    private final PlatformTransactionManager transactionManager;

    /**
     * Creates the runner and fixes the order.
     *
     * @param fixtures           the registered fixtures, in any order
     * @param dataSource         the application's database, or {@code null} when it has none
     * @param transactionManager the application's transaction manager, or {@code null} when it has none
     * @throws IllegalStateException when the fixtures form a cycle, one names a predecessor that is
     *                               not registered, or one type is registered twice
     */
    public SeedFixtures(List<SeedFixture> fixtures, DataSource dataSource,
                        PlatformTransactionManager transactionManager) {
        this.ordered = List.copyOf(order(fixtures));
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
    }

    /**
     * The fixtures in the order they run.
     *
     * @return simple class names, in execution order
     */
    public List<String> names() {
        return ordered.stream().map(SeedFixtures::name).toList();
    }

    /**
     * The fixtures that publish messages.
     *
     * @return simple class names of the fixtures that need a broker; empty when none does
     */
    public List<String> needingKafka() {
        return ordered.stream().filter(SeedFixture::needsKafka).map(SeedFixtures::name).toList();
    }

    /**
     * Applies a mode.
     *
     * @param mode what to do with existing rows
     * @return the names of the fixtures that ran, in order; empty when none did
     */
    public List<String> run(FixtureMode mode) {
        return switch (mode) {
            case RESET -> reset();
            case KEEP -> holdsRows() ? List.of() : seed();
            case NONE -> List.of();
        };
    }

    /**
     * Empties every table except the migration tool's, then runs every fixture.
     *
     * @return the names of the fixtures that ran, in order
     */
    public List<String> reset() {
        if (dataSource != null) {
            new DatabaseCleaner(dataSource).clean();
        }
        return seed();
    }

    /**
     * Runs every fixture against the database as it is.
     *
     * @return the names of the fixtures that ran, in order
     * @throws IllegalStateException naming the fixture that failed; its writes are rolled back and no
     *                               later fixture runs
     */
    // CHECKSTYLE.OFF: IllegalCatch - a fixture is the service's own code and may fail with anything;
    // whatever it throws, the developer needs to be told which fixture it was.
    public List<String> seed() {
        List<String> ran = new ArrayList<>();
        for (SeedFixture fixture : ordered) {
            try {
                if (transactionManager != null) {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> fixture.seed());
                } else {
                    fixture.seed();
                }
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Seed fixture " + name(fixture) + " failed. Fixtures "
                        + "that had already run: " + ran + ".", ex);
            }
            ran.add(name(fixture));
        }
        return ran;
    }
    // CHECKSTYLE.ON: IllegalCatch

    /**
     * Whether a previous run left anything behind.
     *
     * <p>"Any table other than the migration tool's has a row" is the whole test. It is deliberately
     * not "the rows the fixtures would write are present": the point of {@link FixtureMode#KEEP} is to
     * leave alone a database the developer has since changed.
     */
    private boolean holdsRows() {
        if (dataSource == null) {
            return false;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> tables = jdbc.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = ?
                   AND table_type = 'BASE TABLE'
                   AND lower(table_name) NOT LIKE ?
                """,
                String.class, SCHEMA, MIGRATION_TABLES);
        for (String table : tables) {
            Boolean any = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM \"" + SCHEMA + "\".\"" + table + "\")", Boolean.class);
            if (Boolean.TRUE.equals(any)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Orders fixtures so that each runs after everything it names.
     *
     * <p>Ties are broken by class name, so the order is the same on every machine and in every run -
     * a fixture set that only works in one accidental order should fail everywhere, not somewhere.
     */
    private static List<SeedFixture> order(List<SeedFixture> fixtures) {
        Map<Class<?>, SeedFixture> byType = new LinkedHashMap<>();
        fixtures.stream()
                .sorted(Comparator.comparing(fixture -> type(fixture).getName()))
                .forEach(fixture -> {
                    if (byType.putIfAbsent(type(fixture), fixture) != null) {
                        throw new IllegalStateException(
                                "Seed fixture " + name(fixture) + " is registered more than once.");
                    }
                });
        for (SeedFixture fixture : byType.values()) {
            for (Class<? extends SeedFixture> predecessor : fixture.after()) {
                if (!byType.containsKey(predecessor)) {
                    throw new IllegalStateException("Seed fixture " + name(fixture) + " runs after "
                            + predecessor.getSimpleName() + ", which is not registered.");
                }
            }
        }
        List<SeedFixture> sorted = new ArrayList<>();
        Map<Class<?>, SeedFixture> waiting = new LinkedHashMap<>(byType);
        while (!waiting.isEmpty()) {
            SeedFixture next = waiting.values().stream()
                    .filter(fixture -> fixture.after().stream().noneMatch(waiting::containsKey))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Seed fixtures form a cycle: "
                            + waiting.values().stream().map(SeedFixtures::name).toList()
                            + ". Each of them runs after another one of them."));
            waiting.remove(type(next));
            sorted.add(next);
        }
        return sorted;
    }

    private static Class<?> type(SeedFixture fixture) {
        return ClassUtils.getUserClass(fixture);
    }

    private static String name(SeedFixture fixture) {
        return type(fixture).getSimpleName();
    }
}
