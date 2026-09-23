package ru.ludwigandreas.restclient.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.restclient.registrar.LudwigRestClientsRegistrar;

/**
 * Widens or narrows where {@link LudwigRestClient} interfaces are looked for.
 *
 * <p>It is optional. With nothing declared, the starter scans the application's own
 * auto-configuration packages - the packages below {@code @SpringBootApplication} - which is where
 * a service's own client interfaces live, so the common case needs no annotation at all.
 *
 * <p>Declare it when the interfaces are somewhere the scan would not reach: a shared contracts
 * artifact, a package outside the application's root, or a test fixture. Declaring it replaces the
 * default scan rather than adding to it, so a service that names one package and still wants its
 * own must name both - the alternative, silently unioning them, makes it impossible to exclude
 * anything.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import(LudwigRestClientsRegistrar.class)
public @interface EnableLudwigRestClients {

    /** Alias for {@link #basePackages()}. */
    String[] value() default {};

    /** Packages to scan for {@link LudwigRestClient} interfaces. */
    String[] basePackages() default {};

    /**
     * Type-safe alternative to {@link #basePackages()}: the package of each class is scanned.
     *
     * <p>Preferred, because a refactoring that moves a package updates this and does not update a
     * string.
     */
    Class<?>[] basePackageClasses() default {};
}
