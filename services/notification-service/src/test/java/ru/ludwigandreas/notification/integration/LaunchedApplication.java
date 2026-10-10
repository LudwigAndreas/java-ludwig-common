package ru.ludwigandreas.notification.integration;

import org.springframework.context.ConfigurableApplicationContext;
import ru.ludwigandreas.notification.local.LocalNotificationApplication;

/**
 * Starts {@link LocalNotificationApplication} inside the build, with the two arguments the build
 * needs and a developer's run does not.
 *
 * <ul>
 *   <li><b>A random port</b>, because 8080 may be taken on a build machine.</li>
 *   <li><b>A small connection pool.</b> The service's own is 20 connections, and it shares a
 *       100-connection PostgreSQL with every cached test context of the suite.</li>
 * </ul>
 *
 * <p>Nothing else differs from what a developer starts. One thing still differs underneath: the
 * build's test classpath carries {@code user-settings-spring-boot-starter}, which the packaged service
 * and {@code spring-boot:test-run} leave out. With no mode enabled that starter is inert, so the
 * application behaves the same either way.
 */
final class LaunchedApplication {

    private LaunchedApplication() {
    }

    static ConfigurableApplicationContext start() {
        return LocalNotificationApplication.start(
                "--server.port=0",
                "--spring.datasource.hikari.maximum-pool-size=5",
                "--spring.datasource.hikari.minimum-idle=1");
    }
}
