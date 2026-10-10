package ru.ludwigandreas.notification.local;

import org.springframework.context.ConfigurableApplicationContext;
import ru.ludwigandreas.notification.NotificationApplication;
import ru.ludwigandreas.testsupport.local.LocalRun;

/**
 * notification-service, whole, on a developer's machine.
 *
 * <p>Run it from the IDE with a breakpoint set, or with
 * {@code mvn -pl :notification-service spring-boot:test-run}. It starts the service's own
 * {@code main} - nothing is replaced - with PostgreSQL, Kafka, a mail server, stand-ins for the
 * identity provider, the chat API and a webhook receiver, and seed data. When it is up it logs where
 * everything is, and a ready-made command for calling it as each seeded caller.
 *
 * <p>This class is the reference example of {@code test-support-local}; the service README's
 * "Running locally" section walks through it.
 */
public final class LocalNotificationApplication {

    /** The profile whose properties live in {@code src/test/resources/application-localrun.yml}. */
    public static final String PROFILE = "localrun";

    private LocalNotificationApplication() {
    }

    public static void main(String[] args) {
        start(args);
    }

    /**
     * Starts the local application and returns it.
     *
     * <p>Separate from {@link #main} so that the integration tests which keep this example alive
     * start exactly what a developer starts, and can still close it afterwards. They call this and
     * never touch the launcher themselves - which is what lets the architecture test insist that
     * the launcher is only ever used from this package.
     *
     * @param args arguments passed on to the service's main
     * @return the running application
     */
    public static ConfigurableApplicationContext start(String... args) {
        return LocalRun.from(NotificationApplication::main)
                .postgres()
                .kafka()
                .mail()
                .stubs(new NotificationStubs())
                .fixtures(NotificationFixtures.class)
                .profiles(PROFILE)
                .run(args);
    }
}
