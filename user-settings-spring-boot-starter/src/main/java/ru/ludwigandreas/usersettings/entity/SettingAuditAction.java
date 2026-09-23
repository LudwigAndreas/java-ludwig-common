package ru.ludwigandreas.usersettings.entity;

/**
 * What an audit row records having happened.
 *
 * <p>{@link #ADMIN_READ} is here because an administrator looking at another person's settings is an
 * event worth recording even though nothing changed. Every other constant describes a write.
 */
public enum SettingAuditAction {

    /** A value was set or replaced. */
    SET,

    /** A value was removed, so the layers below supply one again. */
    RESET,

    /** An administrator read another subject's settings. */
    ADMIN_READ
}
