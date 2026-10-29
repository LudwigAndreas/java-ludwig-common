package ru.ludwigandreas.security.admin;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the path variable of an administrative handler that names the object it acts on - a reconciliation task,
 * say - so that the operation can be narrowed by data scope.
 *
 * <p>Scoping is opt-in per area and tier: only when the data-scope policy has an entry for resource
 * {@code admin.}<i>area</i> and the operation's tier is the named object checked against the caller's scope along
 * dimension {@code object}. Without that entry, holding the operation admits every object. The object is read from
 * the matched URI template before the handler runs, which is why the parameter must also be a {@code @PathVariable}
 * ({@code admin.admin-object-is-a-path-variable} in {@code architecture-rules}).
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminObject {

    /**
     * The problem code of the area's own "no such object" answer, with the object name as its one argument. An object
     * outside a read caller's scope is answered with it, so the response is the one for an object that does not
     * exist and discloses nothing.
     *
     * @return the not-found problem code
     */
    String notFound() default AdminObjectNotFoundException.DEFAULT_CODE;
}
