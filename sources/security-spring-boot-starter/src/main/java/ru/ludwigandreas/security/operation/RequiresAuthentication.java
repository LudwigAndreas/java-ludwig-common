package ru.ludwigandreas.security.operation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler any authenticated caller may use, because it performs no operation - discovering which operations
 * the caller holds is the canonical one.
 *
 * <p>Explicit rather than implied by a missing {@link RequiresOperation}: an administrative handler carrying neither
 * is refused, so forgetting the annotation fails closed instead of opening the endpoint.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresAuthentication {
}
