package ru.ludwigandreas.observability.core;

import org.springframework.core.env.Environment;

/**
 * Derives a {@link ServiceIdentity} from the environment, so that a service that configures nothing
 * still exports a usable identity.
 *
 * <p>Every field follows the same rule: an explicit {@code ludwig.observability.service.*} value
 * wins, and only when it is absent is something inferred. The inferences are deliberately taken from
 * places that are already correct in a normally-built, normally-deployed Spring Boot service - the
 * application name, the jar manifest, the pod's hostname - rather than from a new convention
 * operators would have to be taught.
 *
 * <p>This is a static derivation over the {@code Environment} rather than a bean, because it is
 * needed twice at two very different points in the lifecycle: once by
 * {@link ru.ludwigandreas.observability.config.ObservabilityEnvironmentPostProcessor}, long before
 * any bean exists, and once by autoconfiguration afterwards. Identical inputs therefore give
 * identical results, and the OTLP resource attributes cannot disagree with the metric tags.
 */
public final class ServiceIdentityResolver {

    private ServiceIdentityResolver() {
    }

    /**
     * @param environment the application environment, which must already have config data loaded
     * @param mainClass   the application's main class, used for the jar manifest version; may be
     *                    {@code null} when it is not known yet
     */
    public static ServiceIdentity resolve(Environment environment, Class<?> mainClass) {
        return new ServiceIdentity(
                resolveName(environment),
                environment.getProperty("ludwig.observability.service.namespace"),
                resolveVersion(environment, mainClass),
                resolveEnvironment(environment),
                resolveInstance(environment));
    }

    private static String resolveName(Environment environment) {
        String configured = environment.getProperty("ludwig.observability.service.name");
        if (hasText(configured)) {
            return configured;
        }
        // The same property Boot's own OpenTelemetry resource and Micrometer's tags already key on,
        // so the default identity matches what a service is called everywhere else.
        return environment.getProperty("spring.application.name");
    }

    private static String resolveVersion(Environment environment, Class<?> mainClass) {
        String configured = environment.getProperty("ludwig.observability.service.version");
        if (hasText(configured)) {
            return configured;
        }
        // Set by the Spring Boot Maven/Gradle plugin in the repackaged jar's manifest, so a normally
        // built service gets its released version without anyone wiring anything. Returns null in an
        // IDE or an exploded classpath, which is correct: there is no released version there, and
        // inventing one would attribute local runs to a real build.
        if (mainClass != null) {
            Package mainPackage = mainClass.getPackage();
            if (mainPackage != null && hasText(mainPackage.getImplementationVersion())) {
                return mainPackage.getImplementationVersion();
            }
        }
        // Present whenever the build-info goal ran; unlike the manifest it also survives an
        // exploded layout, which is how most containers actually run a Boot application.
        return environment.getProperty("build.version");
    }

    private static String resolveEnvironment(Environment environment) {
        String configured = environment.getProperty("ludwig.observability.service.environment");
        if (hasText(configured)) {
            return configured;
        }
        // The first active profile is a guess, and a good enough one that it beats exporting
        // nothing: in practice deployments run with exactly one tier profile. It is documented as a
        // default precisely so a deployment that uses profiles for something else overrides it.
        String[] activeProfiles = environment.getActiveProfiles();
        return activeProfiles.length > 0 ? activeProfiles[0] : null;
    }

    private static String resolveInstance(Environment environment) {
        String configured = environment.getProperty("ludwig.observability.service.instance");
        if (hasText(configured)) {
            return configured;
        }
        // Under Kubernetes this is the pod name, which is exactly the handle an operator needs to go
        // from "one instance is misbehaving" to "kubectl logs <that pod>".
        String hostname = environment.getProperty("HOSTNAME");
        return hasText(hostname) ? hostname : environment.getProperty("COMPUTERNAME");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
