package ru.ludwigandreas.idempotency.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;
import ru.ludwigandreas.idempotency.web.IdempotencyEndpointMatcher;
import ru.ludwigandreas.idempotency.web.IdempotencyFilter;
import ru.ludwigandreas.webcore.config.WebCoreProblemAutoConfiguration;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;

/**
 * The HTTP surface, when there is one and a deployment asked for it.
 *
 * <p>Gated on {@code ludwig.idempotency.http.enabled}, which is <b>off by default</b>. A filter that
 * started deduplicating every matching endpoint the moment this module appeared on a classpath would
 * change the behaviour of live APIs as a side effect of a dependency bump - a caller's second
 * {@code POST} would start returning a replayed {@code 201} instead of creating a second resource, which
 * is the right behaviour and not one to switch on without being asked.
 */
@AutoConfiguration
@ConditionalOnClass({Filter.class, RequestMappingHandlerMapping.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ludwig.idempotency.http", name = "enabled", havingValue = "true")
// Both, and web-core's for a reason that cost a debugging session: the filter below is
// @ConditionalOnBean(ProblemDetailFactory.class), and a @ConditionalOnBean evaluated before the
// autoconfiguration that registers the bean sees nothing. The filter would then simply not exist, the
// endpoints would answer normally, and dedup would be silently off - which is the one failure mode this
// module must not have.
@AutoConfigureAfter({IdempotencyPersistenceAutoConfiguration.class, WebCoreProblemAutoConfiguration.class})
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyWebAutoConfiguration {

    /**
     * Decides which requests are claimed and under which scope.
     *
     * @param properties     the configuration
     * @param handlerMapping Spring MVC's mapping, used to read the {@code @Idempotent} annotation and the
     *                       matched pattern; an {@code ObjectProvider} because a context can be a servlet
     *                       web application without one - an actuator-only context, for instance - and the
     *                       configured patterns still work there
     * @return the matcher
     */
    @Bean
    @ConditionalOnMissingBean
    public IdempotencyEndpointMatcher idempotencyEndpointMatcher(
            IdempotencyProperties properties,
            ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
        // A supplier, not handlerMapping.getIfAvailable(): this matcher belongs to a Filter, which Boot
        // creates before WebMvcAutoConfiguration has produced the mapping, so resolving it here would get
        // null for the lifetime of the application - and with the recommended empty path-patterns list the
        // filter would then match nothing and dedup would be silently off.
        return new IdempotencyEndpointMatcher(properties, handlerMapping::getIfAvailable);
    }

    /**
     * The filter.
     *
     * <p>Conditional on a store, so that switching the HTTP surface on in a service that has no backend
     * wired is a missing-bean report at startup rather than a 500 on the first protected request.
     *
     * @param store        the claim store
     * @param matcher      decides which requests are claimed
     * @param fingerprints hashes a request
     * @param problems     renders the 409 and the 422
     * @param properties   the configuration
     * @param metrics      what this module reports about itself
     * @param audit        the trail a replay and a mismatch are recorded in
     * @param actors       resolves who made the call
     * @param objectMapper serialises the problem document the filter writes by hand
     * @return the filter
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({IdempotencyStore.class, ProblemDetailFactory.class})
    public IdempotencyFilter idempotencyFilter(IdempotencyStore store, IdempotencyEndpointMatcher matcher,
                                               RequestFingerprint fingerprints,
                                               ProblemDetailFactory problems,
                                               IdempotencyProperties properties,
                                               IdempotencyMetrics metrics,
                                               ObjectProvider<AuditSink> audit,
                                               ObjectProvider<ActorResolver> actors,
                                               ObjectProvider<ObjectMapper> objectMapper) {
        return new IdempotencyFilter(store, matcher, fingerprints, problems, properties, metrics,
                audit.getIfAvailable(() -> event -> { }),
                actors.getIfAvailable(ActorResolver::unattributed),
                objectMapper.getIfAvailable(ObjectMapper::new));
    }
}
