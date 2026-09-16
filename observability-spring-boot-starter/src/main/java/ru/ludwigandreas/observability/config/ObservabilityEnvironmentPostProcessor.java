package ru.ludwigandreas.observability.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import ru.ludwigandreas.observability.core.ServiceIdentity;
import ru.ludwigandreas.observability.core.ServiceIdentityResolver;

/**
 * Supplies the defaults that make a service observable without configuring anything, as the
 * lowest-precedence property source in the environment.
 *
 * <h2>Why defaults, and why at the bottom of the stack</h2>
 *
 * <p>Everything written here is a value a production service needs and that Spring Boot leaves unset
 * or sets for a single-process default: probes off, no OTLP identity, no graceful shutdown, Kafka
 * observation disabled. Shipping them as defaults rather than as forced values is the whole point -
 * they are added with {@link MutablePropertySources#addLast}, below system properties, environment
 * variables and every {@code application.yml} value, so a service or a deployment that sets any of
 * them wins without having to know this class exists. Nothing here can override a deliberate choice.
 *
 * <p>The alternative - setting these in autoconfiguration - does not work for most of them. A
 * sampling probability, a probe group and a graceful-shutdown timeout are read by Spring Boot's own
 * autoconfiguration while it builds its beans, so by the time any bean of this module could run,
 * those decisions have already been made.
 *
 * <p>This post-processor deliberately declares no order, which puts it last: config data
 * ({@code application.yml}, profiles, imported config trees) is processed by an ordered
 * post-processor that therefore runs earlier, so by this point the environment is complete enough to
 * resolve the service identity from it - which is what lets the OTLP resource attributes below be
 * derived from the same values the rest of the module will bind.
 */
public class ObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** Named so it is recognisable in {@code /actuator/env} and in an "where did this come from" hunt. */
    static final String PROPERTY_SOURCE_NAME = "ludwig-observability-defaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("ludwig.observability.enabled", Boolean.class, true)) {
            return;
        }
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            // A parent context, or a second SpringApplication in the same JVM (very common in tests),
            // can hand the same environment round twice. Re-adding would be harmless but duplicated.
            return;
        }

        Map<String, Object> defaults = new LinkedHashMap<>();
        applyServiceIdentity(environment, application, defaults);
        applyActuatorDefaults(defaults);
        applyTracingDefaults(defaults);
        applyMetricsDefaults(defaults);
        applyKafkaDefaults(defaults);

        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    /**
     * Resolves the service identity once and publishes it twice: as the module's own
     * {@code ludwig.observability.service.*} properties, and as OpenTelemetry resource attributes.
     *
     * <p>Publishing the resolved values back as properties is what keeps the two in step. The
     * resolver reads any explicitly configured value first, so a service that sets its own
     * {@code ludwig.observability.service.name} gets that name in its resource attributes too, and
     * the autoconfiguration further down simply binds {@code ObservabilityProperties} without
     * repeating - or subtly diverging from - the derivation performed here.
     */
    private void applyServiceIdentity(ConfigurableEnvironment environment, SpringApplication application,
            Map<String, Object> defaults) {
        ServiceIdentity identity = ServiceIdentityResolver.resolve(environment, application.getMainApplicationClass());

        putIfPresent(defaults, "ludwig.observability.service.name", identity.name());
        putIfPresent(defaults, "ludwig.observability.service.namespace", identity.namespace());
        putIfPresent(defaults, "ludwig.observability.service.version", identity.version());
        putIfPresent(defaults, "ludwig.observability.service.environment", identity.environment());
        putIfPresent(defaults, "ludwig.observability.service.instance", identity.instance());

        // Bracket notation, not a dotted suffix: these are entries of a Map<String, String>, and the
        // keys themselves contain dots. Written as
        // "management.opentelemetry.resource-attributes.service.name" the binder would have to guess
        // where the property path ends and the map key begins; the brackets say so explicitly.
        identity.toResourceAttributes().forEach((key, value) ->
                defaults.put("management.opentelemetry.resource-attributes[" + key + "]", value));
    }

    private void applyActuatorDefaults(Map<String, Object> defaults) {
        // Exactly the endpoints an operator and a scraper need, and nothing that discloses
        // configuration. env, configprops, heapdump and threaddump are left off deliberately: they
        // expose credentials and memory contents, and a starter must not be the reason they became
        // reachable in an estate where the management port is not firewalled off.
        defaults.put("management.endpoints.web.exposure.include", "health,info,metrics,prometheus,loggers");

        // Splits health into liveness and readiness. Without it both probes answer from the same
        // aggregate, so a service that has lost its database - unready, but perfectly alive - fails
        // its liveness probe too and gets killed and restarted in a loop that cannot fix anything.
        defaults.put("management.endpoint.health.probes.enabled", "true");
        defaults.put("management.health.livenessstate.enabled", "true");
        defaults.put("management.health.readinessstate.enabled", "true");

        // Component details are genuinely useful and disclose internal topology (hostnames, broker
        // addresses, schema names), so they are shown to an authorized caller rather than to anyone
        // who can reach the port.
        defaults.put("management.endpoint.health.show-details", "when_authorized");
        defaults.put("management.endpoint.health.show-components", "when_authorized");

        // In-flight requests finish instead of being severed mid-response when a pod is rotated.
        // Without this a routine deploy shows up as a burst of client-side 502s, which is
        // indistinguishable from a real incident on a dashboard.
        defaults.put("server.shutdown", "graceful");
        defaults.put("spring.lifecycle.timeout-per-shutdown-phase", "30s");
    }

    private void applyTracingDefaults(Map<String, Object> defaults) {
        // Boot's own default is 0.1 as well; it is restated here so the value is visible in one
        // place next to the force-sampling header that exists to work around it, rather than being
        // an invisible framework constant an operator has to go looking for.
        defaults.put("management.tracing.sampling.probability", "0.1");
    }

    private void applyMetricsDefaults(Map<String, Object> defaults) {
        // Turns a Prometheus histogram bucket into a bucket plus an example trace id, so a spike in
        // the p99 panel is one click away from a trace of a request that was actually that slow.
        // This is the single highest-value link between the metrics and tracing pillars, and it
        // costs nothing once both are already present.
        defaults.put("management.prometheus.metrics.export.histogram-flavor", "prometheus");
    }

    private void applyKafkaDefaults(Map<String, Object> defaults) {
        // Spring Kafka creates producer and consumer spans only when these are on, and they default
        // to off. Without them a trace stops at the send() and resumes as an unrelated trace in the
        // consumer - which is precisely the hop that asynchronous systems are hardest to debug
        // across. Inert when Kafka is not on the classpath.
        defaults.put("spring.kafka.template.observation-enabled", "true");
        defaults.put("spring.kafka.listener.observation-enabled", "true");
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }
}
