package ru.ludwigandreas.asyncapi.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * The Springwolf settings a platform service would otherwise have to repeat, at the lowest precedence so a
 * service can override any of them.
 *
 * <p>Springwolf refuses to build a document without servers, a base package and an info block, and by
 * default documents a {@code ConsumerRecord<String, T>} listener as an empty {@code ConsumerRecord}. Every
 * platform service would set the same values, so they are set once here:
 *
 * <ul>
 *   <li>one server per Kafka cluster - {@code default} and every {@code ludwig.kafka.clusters} key - with
 *       the cluster name as a placeholder host. The committed document drops hosts; the names are what a
 *       reader needs.</li>
 *   <li>base package {@code ru.ludwigandreas}, so listeners contributed by platform starters are part of
 *       the service's surface, as they are at runtime.</li>
 *   <li>title from {@code spring.application.name}, and version {@code current}: the published Nexus
 *       coordinate is the real version, and a version inside the document would make every release a
 *       drift.</li>
 *   <li>{@code ConsumerRecord}'s second type argument as the payload.</li>
 * </ul>
 */
public class AsyncApiDefaultsPostProcessor implements EnvironmentPostProcessor {

    /** The name of the property source holding these defaults. */
    public static final String SOURCE = "ludwigAsyncApiDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("springwolf.docket.base-package", "ru.ludwigandreas");
        defaults.put("springwolf.docket.info.title", environment.getProperty("spring.application.name", "service"));
        defaults.put("springwolf.docket.info.version", "current");
        defaults.put("springwolf.payload.extractable-classes[org.apache.kafka.clients.consumer.ConsumerRecord]", 1);
        // Springwolf's own endpoints are not the service's HTTP API; without this its UI config joins the OpenAPI.
        defaults.put("springdoc.packages-to-exclude", "io.github.springwolf");
        // Springwolf flips swagger's JVM-wide TypeNameResolver to this while it scans; true leaks FQN schema names
        // into a springdoc document generated at the same moment.
        defaults.put("springwolf.use-fqn", false);
        Set<String> clusters = new LinkedHashSet<>();
        clusters.add("default");
        clusters.addAll(Binder.get(environment)
                .bind("ludwig.kafka.clusters", Bindable.mapOf(String.class, Object.class))
                .map(Map::keySet).orElse(Set.of()));
        for (String cluster : clusters) {
            defaults.put("springwolf.docket.servers." + cluster + ".protocol", "kafka");
            defaults.put("springwolf.docket.servers." + cluster + ".host", cluster);
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE, defaults));
    }
}
