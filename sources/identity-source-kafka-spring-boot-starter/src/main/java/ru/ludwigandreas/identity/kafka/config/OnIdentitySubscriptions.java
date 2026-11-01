package ru.ludwigandreas.identity.kafka.config;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches when at least one {@code ludwig.identity.kafka.subscriptions.<source>} entry is configured. */
class OnIdentitySubscriptions extends SpringBootCondition {

    static final String KEY = IdentityKafkaProperties.PREFIX + ".subscriptions";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean any = Binder.get(context.getEnvironment())
                .bind(KEY, Bindable.mapOf(String.class, Object.class))
                .map(map -> !map.isEmpty()).orElse(false);
        return any ? ConditionOutcome.match(KEY + " is configured")
                : ConditionOutcome.noMatch("no " + KEY + " is configured");
    }

    static Map<String, IdentityKafkaProperties.Subscription> bind(Environment env) {
        return Binder.get(env).bind(KEY, Bindable.mapOf(String.class, IdentityKafkaProperties.Subscription.class))
                .orElse(Map.of());
    }
}
