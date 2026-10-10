package ru.ludwigandreas.observability.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.springframework.core.env.Environment;

/**
 * Derives a {@link BuildIdentity} from the two provenance resources the build packaged into the
 * artifact.
 *
 * <h2>Build time, never runtime</h2>
 *
 * <p>Everything here is read from {@code git.properties} and {@code META-INF/build-info.properties}
 * on the classpath - files {@code ludwig-service-parent} writes while the artifact is being built.
 * Nothing invokes {@code git} or looks for a {@code .git} directory, and that is the rule rather
 * than an implementation detail: a running container has no checkout to inspect, and a process that
 * found one would be reporting the machine it runs on instead of the build it came from. Reading
 * the packaged resources is what makes one artifact report the same commit in staging and after
 * promotion to production. ArchUnit's {@code RuleGroup.LOGGING} fails the build on the alternative.
 *
 * <p>These are the same two resources Spring Boot builds its {@code GitProperties} and
 * {@code BuildProperties} beans from. The beans themselves cannot be used: they exist only once the
 * context has refreshed, and the log encoder that needs the commit id is installed long before.
 *
 * <h2>Precedence</h2>
 *
 * <p>Each field is resolved on its own, with the same shape {@link ServiceIdentityResolver} uses: an
 * explicit {@code ludwig.observability.build.*} value wins, and only when it is absent is the
 * resource consulted. A field found in neither place is {@code null}.
 *
 * <h2>This runs before the context exists</h2>
 *
 * <p>The caller is an {@code EnvironmentPostProcessor}, where an exception is a failure to boot. A
 * resource that is missing, unreadable or malformed therefore contributes nothing and is not an
 * error - which is the same outcome as an artifact built without git, a state that has to be
 * survivable anyway.
 */
public final class BuildIdentityResolver {

    static final String GIT_RESOURCE = "git.properties";
    static final String BUILD_INFO_RESOURCE = "META-INF/build-info.properties";

    private static final String PROPERTY_PREFIX = "ludwig.observability.build.";

    private BuildIdentityResolver() {
    }

    /**
     * @param environment the application environment, which must already have config data loaded
     * @param classLoader the application's class loader, which the two resources are read from;
     *                    {@code null} means the one that loaded this class
     */
    public static BuildIdentity resolve(Environment environment, ClassLoader classLoader) {
        Properties git = readResource(classLoader, GIT_RESOURCE);
        Properties buildInfo = readResource(classLoader, BUILD_INFO_RESOURCE);

        return new BuildIdentity(
                resolve(environment, "commit-id", git, "git.commit.id"),
                resolve(environment, "abbreviated-commit-id", git, "git.commit.id.abbrev"),
                resolve(environment, "branch", git, "git.branch"),
                resolve(environment, "timestamp", buildInfo, "build.time"),
                resolve(environment, "ci-build-number", buildInfo, "build.ci.build-number"),
                toFlag(resolve(environment, "dirty", git, "git.dirty")));
    }

    private static String resolve(Environment environment, String property, Properties resource, String key) {
        String configured = environment.getProperty(PROPERTY_PREFIX + property);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return resource.getProperty(key);
    }

    /**
     * {@code true} and {@code false} only. Anything else - including a value nobody wrote - is
     * "not known", because reporting an unreadable flag as clean would be inventing an answer.
     */
    private static Boolean toFlag(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Reads one properties resource, returning an empty set when it is absent or cannot be parsed.
     *
     * <p>Shared with {@link ServiceIdentityResolver}, which reads the version from the build-info
     * resource, so that there is one place that decides what a broken resource means.
     */
    static Properties readResource(ClassLoader classLoader, String name) {
        ClassLoader loader = classLoader != null ? classLoader : BuildIdentityResolver.class.getClassLoader();
        Properties properties = new Properties();
        if (loader == null) {
            return properties;
        }
        try (InputStream stream = loader.getResourceAsStream(name)) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException is what Properties.load throws for a malformed unicode
            // escape. Either way the file is not trustworthy as a whole: half of a provenance file
            // is worse than none, because the half that loaded would be reported as fact.
            return new Properties();
        }
        return properties;
    }
}
