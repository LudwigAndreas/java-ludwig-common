package ru.ludwigandreas.testsupport.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.kafka.KafkaContainer;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * One container per image per JVM, started on first use and never stopped.
 *
 * <h2>Why singletons rather than {@code @Container} fields</h2>
 *
 * <p>This is not a preference, and two modules in this repository had already discovered it the hard
 * way before this class existed.
 *
 * <p>The Testcontainers JUnit extension starts and <em>stops</em> a {@code static @Container} around
 * each test class, while the static field itself is initialised once per JVM. Surefire and failsafe
 * both default to one reused fork per module, so several test classes share a JVM - and the first
 * class to finish stops the container the next one is about to use. Worse, Spring's context cache key
 * has not changed, so the second class happily reuses a context wired to the dead container's port.
 * The symptom is "connection refused" in the second test class and nowhere else, which is a long way
 * from its cause. {@code FileIngestTestBase} documented exactly this failure; {@code PostgresBackedTest}
 * documented the same conclusion independently.
 *
 * <p>Starting a container in a static initialiser and leaving it running is the documented
 * Testcontainers arrangement for sharing one. Nothing closes it: Ryuk removes it when the JVM exits,
 * and a JVM shutdown hook backs that up. Calling {@code close()} would defeat the sharing this class
 * exists to provide, which is why the fields carry {@code @SuppressWarnings("resource")}.
 *
 * <h2>What a shared container costs, and where that cost is paid</h2>
 *
 * <p>A shared container means a test can no longer assume an empty database by construction. That is
 * a real trade and it is made deliberately: per-test isolation becomes a <em>schema</em> concern
 * rather than a container concern, and
 * {@link ru.ludwigandreas.testsupport.data.DatabaseCleaner} exists so that paying it is one line in a
 * {@code @BeforeEach}. Nineteen container starts per reactor build were not buying isolation that the
 * truncate helper cannot give more cheaply.
 *
 * <h2>Reuse between runs</h2>
 *
 * <p>{@code withReuse(true)} is set, which does nothing at all unless a developer opts in with
 * {@code testcontainers.reuse.enable=true} in {@code ~/.testcontainers.properties}. With it, the
 * container outlives the JVM and the next run attaches to it instead of starting one. It is off by
 * default and deliberately not switched on in CI: a reused container carries the previous run's rows,
 * which is a fine trade for a developer's inner loop and a bad one for a build that has to be
 * reproducible.
 *
 * <h2>Lazy, not eager</h2>
 *
 * <p>The holders are separate nested classes so that a module which only needs Postgres never starts
 * Kafka. Class initialisation is the JVM's own thread-safe lazy-init mechanism, so no locking appears
 * here.
 */
public final class Containers {

    private Containers() {
    }

    /**
     * The shared PostgreSQL, started on first call.
     *
     * @return a running container
     */
    public static PostgreSQLContainer<?> postgres() {
        return PostgresHolder.INSTANCE;
    }

    /**
     * The shared Kafka broker, started on first call.
     *
     * <p>{@code org.testcontainers.kafka.KafkaContainer}, the KRaft-native one, and not the older
     * {@code org.testcontainers.containers.KafkaContainer}: KRaft means there is no ZooKeeper container
     * to start and wait for. The consequence is that Spring Boot 3.3's connection-details support does
     * not recognise it, which is why
     * {@link ru.ludwigandreas.testsupport.container.KafkaPropertiesInitializer} exists.
     *
     * @return a running container
     */
    public static KafkaContainer kafka() {
        return KafkaHolder.INSTANCE;
    }

    /**
     * The shared LocalStack, started on first call, with S3 enabled.
     *
     * @return a running container
     */
    public static LocalStackContainer localStack() {
        return LocalStackHolder.INSTANCE;
    }

    /**
     * The shared Redis, started on first call.
     *
     * <p>A {@code GenericContainer} rather than a Testcontainers Redis module, because there is no such module
     * in the mainline distribution and the whole configuration is one exposed port. The port is read from the
     * container, never assumed: 6379 is Redis's port inside the container and Testcontainers maps it to an
     * ephemeral one outside, which is what lets two builds share an agent.
     *
     * @return a running container
     */
    public static GenericContainer<?> redis() {
        return RedisHolder.INSTANCE;
    }

    /** Redis's port inside the container; the mapped port outside it is ephemeral. */
    public static final int REDIS_PORT = 6379;

    private static final class PostgresHolder {

        @SuppressWarnings("resource") // Ryuk reaps it at JVM exit; closing it would defeat sharing.
        private static final PostgreSQLContainer<?> INSTANCE =
                new PostgreSQLContainer<>(LudwigTestImages.POSTGRES).withReuse(true);

        static {
            INSTANCE.start();
        }

        private PostgresHolder() {
        }
    }

    private static final class KafkaHolder {

        @SuppressWarnings("resource") // See PostgresHolder.
        private static final KafkaContainer INSTANCE =
                new KafkaContainer(LudwigTestImages.KAFKA).withReuse(true);

        static {
            INSTANCE.start();
        }

        private KafkaHolder() {
        }
    }

    private static final class RedisHolder {

        @SuppressWarnings("resource") // See PostgresHolder.
        private static final GenericContainer<?> INSTANCE =
                new GenericContainer<>(LudwigTestImages.REDIS)
                        .withExposedPorts(REDIS_PORT)
                        .withReuse(true);

        static {
            INSTANCE.start();
        }

        private RedisHolder() {
        }
    }

    private static final class LocalStackHolder {

        @SuppressWarnings("resource") // See PostgresHolder.
        private static final LocalStackContainer INSTANCE =
                new LocalStackContainer(LudwigTestImages.LOCALSTACK)
                        .withServices(LocalStackContainer.Service.S3)
                        .withReuse(true);

        static {
            INSTANCE.start();
        }

        private LocalStackHolder() {
        }
    }
}
