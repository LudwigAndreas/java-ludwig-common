package ru.ludwigandreas.fileaction.engine;

/**
 * Whether the current caller holds an authority.
 *
 * <h2>Why this exists rather than an annotation on the controller</h2>
 *
 * <p>The authority is <em>per action</em> and lives in configuration, so it is not known at compile time - which is
 * what {@code @PreAuthorize} needs. One controller serves every configured action, so an annotation could only
 * express a single authority for all of them, and a SpEL expression reading the path segment would be a second,
 * unchecked place the action's configuration is interpreted.
 *
 * <p>An interface rather than a direct call into Spring Security because {@code security-spring-boot-starter} is an
 * optional dependency of this module: a deployment with no security and no configured authority is a valid
 * deployment, and the engine must not name a type that is not on its classpath.
 *
 * <h2>There is deliberately no permissive default</h2>
 *
 * <p>The obvious shape is a no-op implementation that answers true when no security is present, and it is exactly
 * wrong: it turns a configured authority into one that silently is not checked, which is the failure mode this
 * whole seam exists to avoid. Instead, {@code FileActionConfigurationValidator} refuses to start an application
 * that declares an authority while security is absent - so by the time a checker is needed, one exists.
 */
@FunctionalInterface
public interface AuthorityChecker {

    /**
     * Whether the caller of the current request holds {@code authority}.
     *
     * @param authority the authority an action declared
     * @return true when the caller holds it
     */
    boolean holds(String authority);
}
