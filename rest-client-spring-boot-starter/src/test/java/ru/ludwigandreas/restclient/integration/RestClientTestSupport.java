package ru.ludwigandreas.restclient.integration;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.restclient.config.RestClientAuthAutoConfiguration;
import ru.ludwigandreas.restclient.config.RestClientAutoConfiguration;
import ru.ludwigandreas.restclient.config.RestClientInterfaceAutoConfiguration;

/**
 * The context the integration tests run in: this starter's auto-configurations, Jackson, and nothing
 * else.
 *
 * <p>Deliberately not {@code @SpringBootTest}. These tests are about one client's behaviour against
 * a stub server, and a full context would add seconds per test and a dependency on whatever else the
 * module happens to put on the classpath - which is exactly the failure mode the
 * {@code @LudwigRestClientTest} slice exists to prevent for consuming services.
 */
final class RestClientTestSupport {

    private RestClientTestSupport() {
    }

    /** A runner with the starter's auto-configurations and the given property lines. */
    static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        RestClientAutoConfiguration.class,
                        RestClientAuthAutoConfiguration.class,
                        RestClientInterfaceAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    /**
     * The property lines for a client with fast, deterministic timings.
     *
     * <p>Every integration test needs the same four lines, and repeating them invites the kind of
     * drift where one test waits 200ms per retry and another waits two seconds for no stated reason.
     */
    static List<String> fastClient(String name, String baseUrl, String... extra) {
        List<String> lines = new ArrayList<>(List.of(
                "ludwig.rest-client.clients." + name + ".base-url=" + baseUrl,
                "ludwig.rest-client.clients." + name + ".connect-timeout=1s",
                "ludwig.rest-client.clients." + name + ".read-timeout=2s",
                "ludwig.rest-client.clients." + name + ".resilience.retry.wait-duration=10ms",
                "ludwig.rest-client.clients." + name + ".resilience.retry.randomized-wait-factor=0.0",
                "ludwig.rest-client.clients." + name + ".resilience.circuit-breaker.enabled=false"));
        lines.addAll(List.of(extra));
        return lines;
    }

    /** {@link #fastClient} as the array the runner wants. */
    static String[] props(List<String>... groups) {
        List<String> all = new ArrayList<>();
        for (List<String> group : groups) {
            all.addAll(group);
        }
        return all.toArray(new String[0]);
    }
}
