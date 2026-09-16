package ru.ludwigandreas.observability.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who this process is, in the four dimensions every telemetry backend groups by.
 *
 * <p>The same values are stamped onto metrics (as common tags), onto spans (as OpenTelemetry
 * resource attributes) and onto every log line, which is the entire point of putting them in one
 * object: a dashboard filtered to {@code service=catalog, environment=prod} and a trace search
 * filtered the same way have to select the same processes, and they only do if all three exporters
 * were handed identical strings. When each signal derives its own identity - metrics from
 * {@code spring.application.name}, traces from an {@code OTEL_SERVICE_NAME} variable someone set in
 * a Helm chart - they drift, and the drift is only discovered during an incident.
 *
 * @param name        the service, normally {@code spring.application.name}
 * @param namespace   the bounded context or team the service belongs to; used to disambiguate two
 *                    services that legitimately share a name in different domains
 * @param version     the deployed build, used to attribute a latency change to a release
 * @param environment the deployment tier ({@code prod}, {@code staging}, ...)
 * @param instance    this replica, used to spot one bad pod behind an otherwise healthy average
 */
public record ServiceIdentity(String name, String namespace, String version, String environment, String instance) {

    /** OpenTelemetry semantic-convention attribute keys, so backends recognise these without mapping. */
    public static final String ATTRIBUTE_SERVICE_NAME = "service.name";
    public static final String ATTRIBUTE_SERVICE_NAMESPACE = "service.namespace";
    public static final String ATTRIBUTE_SERVICE_VERSION = "service.version";
    public static final String ATTRIBUTE_SERVICE_INSTANCE_ID = "service.instance.id";
    public static final String ATTRIBUTE_DEPLOYMENT_ENVIRONMENT = "deployment.environment";

    public ServiceIdentity {
        name = blankToNull(name);
        namespace = blankToNull(namespace);
        version = blankToNull(version);
        environment = blankToNull(environment);
        instance = blankToNull(instance);
    }

    /**
     * The identity as OpenTelemetry resource attributes, omitting anything unset.
     *
     * <p>Unset fields are omitted rather than exported as {@code "unknown"}: an absent attribute is
     * visibly absent in a backend's facet list, whereas a literal {@code "unknown"} looks like a
     * real value and silently becomes a group that several unrelated services share.
     */
    public Map<String, String> toResourceAttributes() {
        Map<String, String> attributes = new LinkedHashMap<>();
        putIfPresent(attributes, ATTRIBUTE_SERVICE_NAME, name);
        putIfPresent(attributes, ATTRIBUTE_SERVICE_NAMESPACE, namespace);
        putIfPresent(attributes, ATTRIBUTE_SERVICE_VERSION, version);
        putIfPresent(attributes, ATTRIBUTE_SERVICE_INSTANCE_ID, instance);
        putIfPresent(attributes, ATTRIBUTE_DEPLOYMENT_ENVIRONMENT, environment);
        return Map.copyOf(attributes);
    }

    /**
     * The identity as metric tags, omitting anything unset.
     *
     * <p>Deliberately <em>not</em> the resource-attribute keys: a dotted key like
     * {@code service.name} is not a legal Prometheus label and gets silently rewritten to
     * {@code service_name} by the registry's naming convention. Naming them here the way they will
     * be scraped keeps a PromQL query and this record readable as the same thing.
     *
     * <p>{@code instance} is excluded on purpose. Prometheus already attaches an {@code instance}
     * label from the scrape target, and a second one from inside the process both collides with it
     * and multiplies every series in the registry by the number of replicas - the classic way a
     * metrics bill doubles after a "harmless" tagging change.
     */
    public Map<String, String> toCommonMetricTags() {
        Map<String, String> tags = new LinkedHashMap<>();
        putIfPresent(tags, "service", name);
        putIfPresent(tags, "namespace", namespace);
        putIfPresent(tags, "version", version);
        putIfPresent(tags, "environment", environment);
        return Map.copyOf(tags);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
