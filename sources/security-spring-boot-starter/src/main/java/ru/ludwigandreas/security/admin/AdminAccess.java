package ru.ludwigandreas.security.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the administrative operation a handler method performs, and therefore who may call it.
 *
 * <p>Evaluated by the administrative surface guard before the handler runs, on every request under
 * {@code /admin/}, whatever filter chain and whatever method-security configuration the service has. The value is
 * a catalogued operation id - write it as the declaring module's constant, so that a typo is a compile error
 * rather than the startup failure it would otherwise be.
 *
 * <p>Never combined with {@code @PreAuthorize} or a role check: the grant is the service's decision, made in
 * {@code ludwig.admin.grants}, and a second gate on the same endpoint is a second place to change it.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAccess {

    /**
     * The operation this handler performs.
     *
     * @return an operation id such as {@code outbox.operate}
     */
    String value();
}
