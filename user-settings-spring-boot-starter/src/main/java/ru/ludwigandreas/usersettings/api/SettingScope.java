package ru.ludwigandreas.usersettings.api;

/**
 * One addressable place a value can be stored: a layer plus the id of the thing at that layer.
 *
 * <p>The pair is what a row is keyed on, and what a {@link SettingScopeResolver} produces. A user
 * scope's id is the subject, a role scope's id is the role code, a tenant scope's id is the tenant
 * id. {@link SettingLayer#PLATFORM} and {@link SettingLayer#DEFAULT} have no meaningful id and use
 * {@link #GLOBAL_ID}, so that a scope is never carrying a null the rest of the code has to branch on.
 *
 * @param layer   which layer this scope belongs to
 * @param scopeId the id within that layer
 */
public record SettingScope(SettingLayer layer, String scopeId) {

    /** The id used by layers that address the whole deployment rather than one entity. */
    public static final String GLOBAL_ID = "*";

    /** Rejects a scope with no id, so no code downstream has to branch on a null scope id. */
    public SettingScope {
        if (layer == null) {
            throw new IllegalArgumentException("A setting scope needs a layer");
        }
        if (scopeId == null || scopeId.isBlank()) {
            throw new IllegalArgumentException("A setting scope needs a non-blank id (layer=" + layer + ")");
        }
    }

    public static SettingScope user(String subject) {
        return new SettingScope(SettingLayer.USER, subject);
    }

    public static SettingScope role(String roleCode) {
        return new SettingScope(SettingLayer.ROLE, roleCode);
    }

    public static SettingScope tenant(String tenantId) {
        return new SettingScope(SettingLayer.TENANT, tenantId);
    }

    public static SettingScope platform() {
        return new SettingScope(SettingLayer.PLATFORM, GLOBAL_ID);
    }
}
