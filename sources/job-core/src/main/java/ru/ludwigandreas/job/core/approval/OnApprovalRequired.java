package ru.ludwigandreas.job.core.approval;

import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code ludwig.admin.approval.required} lists at least one operation, in whichever form it is written
 * - an indexed list or a comma-separated value - which a property-presence condition cannot tell apart.
 */
public class OnApprovalRequired implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("ludwig.admin.approval.required", Bindable.listOf(String.class))
                .map(List::isEmpty).map(empty -> !empty).orElse(false);
    }
}
