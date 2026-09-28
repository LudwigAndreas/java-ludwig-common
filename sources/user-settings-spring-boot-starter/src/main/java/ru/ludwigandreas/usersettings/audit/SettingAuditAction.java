package ru.ludwigandreas.usersettings.audit;

/**
 * What an audit event records having happened.
 *
 * <p>{@link #ADMIN_READ} is here because an administrator looking at another person's settings is an
 * event worth recording even though nothing changed. Every other constant describes a write.
 *
 * <p>In the {@code audit} package rather than {@code entity} since the consolidation: the entity it used to
 * be a column of is gone into {@code audit_event}, and this enum is now the authoring vocabulary the writer
 * uses. The {@code audit-002} changeset maps its three values onto the action names below so a migrated row
 * and a new one carry the same string.
 */
public enum SettingAuditAction {

    /** A value was set or replaced. */
    SET("setting.set"),

    /** A value was removed, so the layers below supply one again. */
    RESET("setting.reset"),

    /** An administrator read another subject's settings. */
    ADMIN_READ("setting.admin-read");

    private final String action;

    SettingAuditAction(String action) {
        this.action = action;
    }

    /** The dotted, stable action name this appears under in the platform trail. */
    public String action() {
        return action;
    }
}
