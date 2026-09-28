package ru.ludwigandreas.reconciliation.api;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link SyncTask} bean and binds it to a configured task.
 *
 * <p>The value must match a key under {@code ludwig.reconciliation.tasks}. The two halves are checked
 * against each other at startup, in both directions: a bean whose name matches no configuration, and
 * a configured task with no bean, both fail the context with a message naming the mismatch. Neither
 * failure is recoverable at runtime and both are silent otherwise - a task with no configuration
 * would simply never be scheduled, and configuration with no task would look like a working
 * integration that never runs.
 *
 * <p>Meta-annotated {@code @Component}, so a task is picked up by the application's own component
 * scan without a second registration mechanism.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface ReconciliationTask {

    /**
     * The configured task name this bean implements.
     *
     * @return a key under {@code ludwig.reconciliation.tasks}
     */
    String value();
}
