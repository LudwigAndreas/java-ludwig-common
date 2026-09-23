package ru.ludwigandreas.usersettings.api;

import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.exception.SettingsAccessDeniedException;
import ru.ludwigandreas.usersettings.exception.TenantUnresolvableException;
import ru.ludwigandreas.usersettings.exception.UnknownSettingException;

/**
 * Reading settings. The one interface a consuming service is written against.
 *
 * <p>Owner mode and projection mode both publish an implementation of exactly this type, which is
 * the reason the split is invisible to callers: the service that owns the settings and the service
 * that keeps a replica of them run the same code against the same API, and moving a service from one
 * to the other is a configuration change rather than a rewrite. Nothing on this interface says where
 * the data is, and nothing should be added that does.
 *
 * <h2>Two ways to name the subject</h2>
 *
 * <p>The {@link PrincipalRef} overloads resolve the tenant from the current security context, which
 * is what request-handling code wants. The {@link SettingsSubject} overloads take the tenant
 * explicitly, which is what code running outside a request - a queue worker fanning out a
 * notification, a scheduled job - must use, because it has no security context and a tenant guessed
 * in that situation is a tenant leak waiting to happen.
 *
 * <h2>Access</h2>
 *
 * <p>A caller may read their own settings. Reading another subject's requires the configured
 * administrative authority and the same tenant, and is audited. Enforced here rather than in each
 * caller, because a rule reimplemented per endpoint is a rule one endpoint will get wrong.
 */
public interface SettingsLookup {

    /**
     * One setting, resolved for this subject.
     *
     * @throws UnknownSettingException        when the definition was never registered
     * @throws SettingsAccessDeniedException  when the caller may not read this subject's settings
     * @throws TenantUnresolvableException    when no tenant could be determined
     */
    <T> T get(PrincipalRef ref, SettingDefinition<T> definition);

    /** As above, with the tenant named explicitly rather than taken from the security context. */
    <T> T get(SettingsSubject subject, SettingDefinition<T> definition);

    /**
     * Every declared setting for this subject, resolved in one database query.
     *
     * <p>Prefer this whenever more than one setting is needed for the same subject. Calling
     * {@link #get} in a loop is the N+1 this method exists to prevent.
     */
    ResolvedSettings getAll(PrincipalRef ref);

    /** As above, with the tenant named explicitly rather than taken from the security context. */
    ResolvedSettings getAll(SettingsSubject subject);
}
