package ru.ludwigandreas.notification.local;

import org.springframework.context.annotation.Import;

/**
 * The local application's seed data, named once.
 *
 * <p>Neither this class nor the fixtures carry a stereotype annotation, so no test context has them
 * unless it asks. The local application asks with {@code LocalRun.fixtures(NotificationFixtures.class)}.
 *
 * <p>{@link LocalApi} and {@link LocalCallersSummary} are not fixtures; they are what the fixtures
 * and the start-up summary are built from, and they ride along here so that one class is the whole
 * of "what the local run adds".
 *
 * <p>Nothing here may use a class that is only on the test classpath of the build. The local
 * application is also started by {@code spring-boot:test-run}, which runs with the classpath the
 * Spring Boot plugin would package - without {@code user-settings-spring-boot-starter}, for one.
 */
@Import({
        LocalApi.class,
        LocalCallersSummary.class,
        RecipientSeed.class,
        NotificationSeed.class,
        AnnouncementSeed.class,
        IngressSeed.class
})
public class NotificationFixtures {
}
