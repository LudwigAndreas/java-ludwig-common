package ru.ludwigandreas.archrules.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import ru.ludwigandreas.archrules.config.ArchitectureRulesProperties;

/**
 * Declares what a service wants checked, on the test class that extends
 * {@link ArchitectureRulesTest}.
 *
 * <pre>{@code
 * @AnalyzeArchitecture(packagesOf = OrdersApplication.class, disable = "kafka")
 * class ArchitectureTest extends ArchitectureRulesTest {
 * }
 * }</pre>
 *
 * <p>Settings are layered: the properties file first, this annotation on top of it, and finally
 * {@link ArchitectureRulesTest#customize} - so a service can keep the bulk of its configuration in
 * {@code architecture-rules.properties} and still override one toggle here.
 *
 * <p>The annotation is discovered through JUnit's annotation support, meta-annotations included, so
 * a platform team can wrap it: an own {@code @AcmeArchitecture} annotated with this one gives every
 * service the organisation's defaults in a single line.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AnalyzeArchitecture {

    /** Base packages to analyse, e.g. {@code "com.acme.orders"}. */
    String[] packages() default {};

    /** Base packages taken from these classes - usually the Spring Boot application class. */
    Class<?>[] packagesOf() default {};

    /**
     * Module/bounded-context packages. Left empty they are discovered as the direct sub-packages of
     * the base packages, which is what most services want.
     */
    String[] modules() default {};

    /** Rules or groups to switch on, e.g. {@code "domain-isolation"}. */
    String[] enable() default {};

    /** Rules or groups to switch off, e.g. {@code "kafka"} or {@code "spring.beans-use-constructor-injection"}. */
    String[] disable() default {};

    /**
     * Classpath resource holding the properties, or {@code ""} to read none. Defaults to
     * {@code architecture-rules.properties}, which is simply absent in most services.
     */
    String properties() default ArchitectureRulesProperties.DEFAULT_RESOURCE;

    /** Whether a rule matching no class passes. {@link Toggle#DEFAULT} leaves the setting alone. */
    Toggle allowEmptyShould() default Toggle.DEFAULT;

    /**
     * Whether today's violations are recorded as the accepted baseline and only new ones fail
     * (ArchUnit's {@code FreezingArchRule}) - the way into an existing codebase.
     */
    Toggle freeze() default Toggle.DEFAULT;

    /** Tri-state switch, so that not setting a flag is different from setting it to false. */
    enum Toggle {

        /** Keep whatever the properties file or the builder said. */
        DEFAULT,

        ENABLED,

        DISABLED;

        /**
         * Passes the requested value to {@code setter} only when this toggle says anything at all,
         * so that leaving it at {@link #DEFAULT} cannot overwrite what the properties file set.
         */
        public void ifSpecified(java.util.function.Consumer<Boolean> setter) {
            if (this != DEFAULT) {
                setter.accept(this == ENABLED);
            }
        }
    }
}
