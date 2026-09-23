package ru.ludwigandreas.usersettings.resolve;

/**
 * On whose behalf a settings operation is running.
 *
 * <p>Distinguished because the three cases are audited differently: a subject reading their own
 * settings is not worth a row, an administrator reading somebody else's is, and in-process code with
 * no caller at all has nobody to attribute anything to.
 */
public enum SettingsAccess {

    /** The caller is the subject. */
    SELF,

    /** The caller holds the administrative authority and is acting on another subject. Audited. */
    ADMIN,

    /**
     * There is no authenticated caller: a queue worker, a scheduled job, a projection consumer.
     *
     * <p>Allowed, because such code is inside the trust boundary by construction - an HTTP request
     * cannot reach a service without the security filter chain establishing a principal first, so an
     * empty security context means the call did not come from outside. A deployment that wants this
     * closed anyway sets {@code ludwig.user-settings.access.allow-unauthenticated=false}, and the
     * background paths then have to present a system principal.
     */
    SYSTEM
}
