package ru.ludwigandreas.security.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.security.audit.PrincipalActorResolver;

/**
 * Teaches the platform's one actor resolution about this module's principal.
 *
 * <p>Its own autoconfiguration, and deliberately <strong>not</strong> gated on
 * {@code ludwig.security.enabled}. That property governs whether this module installs a filter chain and
 * enforces authorization; actor resolution is a different thing, and it feeds two consumers -
 * {@code AuditEvent.actor} and {@code db-core}'s {@code created_by} / {@code last_modified_by} stamping.
 * Gating it would mean that switching the security starter off silently changed which name appears in every
 * entity's audit columns and every audit event, from the person's subject to {@code system}, with nothing to
 * say so. A wrong value in a trail retained for years is a worse failure than a missing bean.
 *
 * <p>{@code audit-core} ships two resolvers of its own - one over plain {@code Authentication.getName()},
 * one unattributed - and both yield to this, which is ordered ahead of them by the {@code afterName} on
 * {@code AuditCoreAutoConfiguration}. That indirection exists because {@code audit-core} depends on nothing
 * in this repository, which is precisely what lets this module depend on it: it cannot know
 * {@code LudwigPrincipal} exists.
 *
 * <p>Why the plain resolver is not enough here: {@code Authentication.getName()} on a token whose principal
 * is a {@link ru.ludwigandreas.security.principal.LudwigPrincipal} answers with that object's
 * {@code toString()}, not with its subject. The trail would then carry a rendered object where a joinable id
 * belongs.
 */
@AutoConfiguration
public class SecurityAuditAutoConfiguration {

    /**
     * The actor, from this platform's own principal.
     *
     * @return the resolver
     */
    @Bean
    @ConditionalOnMissingBean(ActorResolver.class)
    public ActorResolver ludwigPrincipalActorResolver() {
        return new PrincipalActorResolver();
    }
}
