package ru.ludwigandreas.security.operation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the operation a method performs, and therefore who may call it - the only authorization code declares.
 *
 * <p>Enforced on an administrative handler by the administrative surface guard, on any other bean method by method
 * security, and on a gRPC method by the server's operation interceptor; all three ask the same {@link
 * OperationEvaluator}. The value is a catalogued operation id: write it as the declaring module's constant, so that a
 * typo is a compile error rather than the startup failure it would otherwise be.
 *
 * <p>Never combined with a role check: which roles hold the operation is the deployment's {@code
 * ludwig.security.roles}, and a second gate on the same method is a second place to change it.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresOperation {

    /**
     * The operation this method performs.
     *
     * @return an operation id such as {@code outbox.message.retry}
     */
    String value();
}
