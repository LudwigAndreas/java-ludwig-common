package ru.ludwigandreas.security.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an administrative handler that any caller the surface guard admits may use, because it performs no
 * operation - discovering which operations the caller holds is the one such handler today.
 *
 * <p>Explicit rather than implied by a missing {@link AdminAccess}: a handler under {@code /admin/} carrying
 * neither annotation is refused, so forgetting the annotation fails closed instead of opening the endpoint.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAuthenticated {
}
