package ru.ludwigandreas.reconciliation.api;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link EnumerationTask} bean and names its configuration block.
 *
 * <p>The counterpart of {@link ReconciliationTask} for the second task kind. The value is a key under
 * {@code ludwig.reconciliation.enumerations} and must equal the bean's {@link EnumerationTask#name()};
 * the startup validator refuses a context where they disagree. Meta-annotated {@code @Component}, so
 * component scanning creates the bean - declaring it again as a {@code @Bean} gives the task two beans.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface ReconciliationEnumeration {

    /**
     * The task's name.
     *
     * @return a key under {@code ludwig.reconciliation.enumerations}
     */
    String value();
}
