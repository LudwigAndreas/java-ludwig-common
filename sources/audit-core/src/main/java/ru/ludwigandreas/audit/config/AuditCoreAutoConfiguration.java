package ru.ludwigandreas.audit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.AuditFailurePolicyResolver;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.AuditSinkFailureListener;
import ru.ludwigandreas.audit.CompositeAuditSink;
import ru.ludwigandreas.audit.CorrelationProvider;
import ru.ludwigandreas.audit.FailurePolicyAuditSink;
import ru.ludwigandreas.audit.NoopAuditSink;
import ru.ludwigandreas.audit.Slf4jAuditSink;
import ru.ludwigandreas.audit.SpringSecurityActorResolver;
import ru.ludwigandreas.audit.redaction.CompositeSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.ConfiguredNamesSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.KeyNameSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.ProvenanceSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.Redactor;
import ru.ludwigandreas.audit.redaction.SensitivityClassifier;

/**
 * Wires the one audit sink every module in this platform is given, with no database and no scheduler.
 *
 * <p><b>It deliberately contributes no {@code Clock} bean.</b> An earlier version did, guarded by
 * {@code @ConditionalOnMissingBean(Clock.class)}, and that guard is not enough: two autoconfigurations with
 * no ordering between them each see no {@code Clock} and each register one, so a service with this module and
 * {@code rest-client-spring-boot-starter} ended up with {@code auditClock} and {@code ludwigRestClientClock}
 * both present - and {@code export-spring-boot-starter}, which injects {@code Clock} by type, failed to start
 * on an ambiguity neither module caused alone. A library must not put an unqualified bean of a
 * java.time-level type into a consumer's context; the sinks that need one take an
 * {@code ObjectProvider<Clock>} and fall back to {@link java.time.Clock#systemUTC()}.
 *
 * <p>It lives here rather than in {@code audit-spring-boot-starter} because the modules that most need a
 * trail are the ones that cannot have that starter: {@code security-spring-boot-starter},
 * {@code rest-client-spring-boot-starter} and {@code hot-reload-spring-boot-starter} all sit below
 * {@code db-core}. Taking a dependency on {@code audit-core} has to be enough to get them a working
 * {@code AuditSink}, and the SLF4J sink alone is a complete configuration - it is what an estate whose log
 * pipeline is already its audit pipeline actually wants.
 *
 * <h2>The bean a module receives</h2>
 *
 * <p>{@code @Primary}, and it is a {@link FailurePolicyAuditSink} in front of a {@link CompositeAuditSink}
 * of whichever sinks are present - including the JPA and outbox sinks {@code audit-spring-boot-starter}
 * contributes when it is on the classpath. The layering is what lets a module have no {@code catch} around
 * {@code record} of its own: the composite guarantees every sink is attempted, and the policy decorator
 * decides whether the caller survives one of them failing. A module that adds its own try/catch is
 * overriding a deployment's configured policy with a hard-coded one.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ludwig.audit", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditCoreAutoConfiguration {

    /**
     * The sensitivity rules a deployment configured, as one classifier.
     *
     * <p>A union of the enabled rules - see {@link SensitivityClassifier#anyOf}, which explains why it has
     * to be a union and not a priority order.
     *
     * <p>Guarded by name rather than by type, deliberately. A type guard would make this bean disappear the
     * moment any module contributed a classifier of its own - user-settings contributes one for its PII
     * declarations - and a deployment's configured rules would then silently stop applying. What composes
     * the two is {@link #auditSensitivityClassifier}.
     *
     * @param properties the configured rules
     * @return the classifier
     */
    @Bean
    @ConditionalOnMissingBean(name = "auditConfiguredSensitivityClassifier")
    public SensitivityClassifier auditConfiguredSensitivityClassifier(AuditProperties properties) {
        AuditProperties.Redaction redaction = properties.getRedaction();
        List<SensitivityClassifier> classifiers = new ArrayList<>();
        if (redaction.isKeyNameHeuristic()) {
            classifiers.add(redaction.getKeyNamePattern() == null
                    ? new KeyNameSensitivityClassifier()
                    : new KeyNameSensitivityClassifier(Pattern.compile(redaction.getKeyNamePattern())));
        }
        classifiers.add(new ProvenanceSensitivityClassifier(redaction.getSensitiveProvenancePrefixes()));
        classifiers.add(new ConfiguredNamesSensitivityClassifier(redaction.getSensitiveNames()));
        return SensitivityClassifier.anyOf(classifiers);
    }

    /**
     * Every classifier in the context, as one.
     *
     * <p>{@code @Primary}, because there are several {@code SensitivityClassifier} beans by design: this
     * module contributes the deployment's configured rules, and a module contributes its own where it knows
     * something no configuration could - user-settings knows which settings are declared as personal data.
     * "Contributing" has to mean composing rather than competing, or the last bean to be defined would
     * silently replace the rest.
     *
     * <p>A union, so a module can only ever widen what the platform masks. That is the direction that is
     * safe: narrowing is the operation that leaks, and a module must not be able to undo a platform rule.
     *
     * @param classifiers every classifier bean, this module's included
     * @return the union
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "auditSensitivityClassifier")
    public SensitivityClassifier auditSensitivityClassifier(ObjectProvider<SensitivityClassifier> classifiers) {
        return new CompositeSensitivityClassifier(classifiers.orderedStream()
                // The composed bean is itself a classifier, so it would otherwise collect itself.
                .filter(one -> !(one instanceof CompositeSensitivityClassifier))
                .toList());
    }

    /** The masking half: one redactor over every classifier in the context. */
    @Bean
    @ConditionalOnMissingBean(Redactor.class)
    public Redactor auditRedactor(SensitivityClassifier classifier) {
        return new Redactor(classifier);
    }

    /**
     * The shipped SLF4J sink, on a logger of its own so the trail can be routed separately.
     *
     * <p>Ordered first in the composite, ahead of any database or broker sink: an event that reached the
     * log and not the table is recoverable from a log line, and the reverse is not.
     *
     * @return the sink
     */
    @Bean
    @Order(0)
    @ConditionalOnProperty(prefix = "ludwig.audit.slf4j", name = "enabled", matchIfMissing = true)
    @ConditionalOnMissingBean(Slf4jAuditSink.class)
    public Slf4jAuditSink slf4jAuditSink() {
        return new Slf4jAuditSink();
    }

    /**
     * The failure policy, from configuration.
     *
     * @param properties the configured policies
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean(AuditFailurePolicyResolver.class)
    public AuditFailurePolicyResolver auditFailurePolicyResolver(AuditProperties properties) {
        return AuditFailurePolicyResolver.ofCategories(properties.getFailure().getByCategory(),
                properties.getFailure().getDefaultPolicy());
    }

    /**
     * The sink every module is injected with.
     *
     * <p>{@code @Primary} because a module asks for {@code AuditSink} by type and there are several beans
     * of that type by design. Without this the injection would be ambiguous and a module would have to name
     * a bean, which is how one module ends up bypassing the policy decorator.
     *
     * <p>{@link NoopAuditSink} when nothing is enabled, rather than no bean at all: "auditing is off in this
     * deployment" must not be the same condition as "this module failed to start".
     *
     * @param sinks     every sink bean, in {@link Order} order
     * @param policies  what to do when one fails
     * @param listeners the failure listener, when a metrics stack is present
     * @return the composed, policy-guarded sink
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "auditSink")
    public AuditSink auditSink(ObjectProvider<AuditSink> sinks,
                               AuditFailurePolicyResolver policies,
                               ObjectProvider<AuditSinkFailureListener> listeners) {
        List<AuditSink> delegates = sinks.orderedStream()
                // The composed bean is itself an AuditSink, so it would otherwise collect itself.
                .filter(sink -> !(sink instanceof FailurePolicyAuditSink))
                .filter(sink -> !(sink instanceof CompositeAuditSink))
                .toList();
        AuditSink composed = delegates.isEmpty() ? NoopAuditSink.INSTANCE
                : new CompositeAuditSink(delegates);
        return new FailurePolicyAuditSink(composed, policies,
                listeners.getIfAvailable(AuditSinkFailureListener::noop));
    }

    /**
     * Actor resolution for a context with no security model at all.
     *
     * <p>The three resolvers in this platform are made mutually exclusive <strong>by classpath</strong>
     * rather than ordered by autoconfiguration precedence, and that is the result of two failed attempts:
     *
     * <ul>
     *   <li>{@code security-spring-boot-starter}'s {@code PrincipalActorResolver} is used when
     *       {@code LudwigPrincipal} is present, because only it knows that type;</li>
     *   <li>{@link SpringSecurityActorResolver} when Spring Security is present and that type is not;</li>
     *   <li>this one when neither is.</li>
     * </ul>
     *
     * <p>Ordering was tried first - {@code @AutoConfiguration(afterName = ...)} naming the security starter -
     * and does not hold for a bean declared by a nested member class, which is what the Spring Security
     * resolver has to be (see {@link SecurityAwareActorConfiguration}). Measured, the plainer resolver won
     * anyway and an administrator's own id silently became {@code system} in every audit event. That is the
     * failure mode worth engineering against: it is a wrong value in a trail retained for years, not a
     * startup error, so nothing announces it.
     *
     * <p>{@code @ConditionalOnMissingClass} takes its classes as strings, so evaluating these conditions
     * never has to load a class that is by definition absent - and it lets this module name a type belonging
     * to a module that depends on it, without depending on that module.
     *
     * <p>The invariant the exclusivity relies on: {@code LudwigPrincipal} on the classpath means
     * {@code security-spring-boot-starter} is present, and its {@code SecurityAuditAutoConfiguration} is
     * ungated, so a resolver always exists. A service that explicitly excludes that autoconfiguration while
     * keeping the jar gets no {@code ActorResolver} and is told so at startup - which is the right failure:
     * loud, at boot, rather than a trail that quietly attributes everything to {@code system}.
     *
     * @return the unattributed resolver
     */
    @Bean
    @ConditionalOnMissingClass({
            "org.springframework.security.core.Authentication",
            "ru.ludwigandreas.security.principal.LudwigPrincipal"})
    @ConditionalOnMissingBean(ActorResolver.class)
    public ActorResolver unattributedActorResolver() {
        return ActorResolver.unattributed();
    }

    /**
     * Actor resolution from the Spring Security context, when nothing more specific is published.
     *
     * <p>A nested configuration with the condition at <em>class</em> level, not a {@code @Bean} method with
     * {@code @ConditionalOnClass} on it. The method form looks equivalent and is not: the condition was
     * evaluated as met on a classpath without Spring Security, and the context then failed to start with a
     * {@code NoClassDefFoundError} introspecting {@link SpringSecurityActorResolver}. A class-level condition
     * is read from bytecode and the nested class - which is what mentions the resolver - is never loaded when
     * it does not hold.
     *
     * <p>It steps aside when {@code security-spring-boot-starter} is on the classpath, by the absence of
     * {@code LudwigPrincipal} rather than by autoconfiguration ordering: that starter's
     * {@code PrincipalActorResolver} is the only resolver that can read a subject out of this platform's own
     * principal, and {@code Authentication.getName()} on such a token answers with the object's
     * {@code toString()} instead.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Authentication.class)
    @ConditionalOnMissingClass("ru.ludwigandreas.security.principal.LudwigPrincipal")
    static class SecurityAwareActorConfiguration {

        @Bean
        @ConditionalOnMissingBean(ActorResolver.class)
        public ActorResolver springSecurityActorResolver() {
            return new SpringSecurityActorResolver();
        }
    }

    /**
     * Correlation from nothing, unless something better is published.
     *
     * <p>{@code observability-spring-boot-starter} and {@code web-core} both have an id to offer, and both
     * are optional; binding to either here would make an optional dependency mandatory in practice, which
     * this module cannot do at all - it has no in-repo dependencies. A service with either publishes the
     * adapter bean; the audit starter's README shows the three lines.
     *
     * @return the empty provider
     */
    @Bean
    @ConditionalOnMissingBean(CorrelationProvider.class)
    public CorrelationProvider noCorrelationProvider() {
        return CorrelationProvider.none();
    }
}
