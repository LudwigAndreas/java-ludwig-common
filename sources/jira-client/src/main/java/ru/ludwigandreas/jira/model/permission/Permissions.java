package ru.ludwigandreas.jira.model.permission;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code {"permissions": {...}}} envelope returned by {@code /mypermissions} and {@code /permissions}.
 *
 * <p>{@link #isGranted(String)} answers the question these endpoints exist for, and answers {@code false}
 * for a permission Jira did not mention at all - which is the safe reading: an unknown permission is not a
 * held permission.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Permissions(Map<String, JiraPermission> permissions) {

    /** Normalizes {@code permissions} to an immutable empty map rather than {@code null}. */
    public Permissions {
        permissions = permissions == null ? Map.of() : Map.copyOf(permissions);
    }

    /** One permission by key, empty when Jira did not report it. */
    public Optional<JiraPermission> get(String key) {
        return Optional.ofNullable(permissions.get(key));
    }

    /** Whether the calling user holds the named permission in the queried context. */
    public boolean isGranted(String key) {
        return get(key).map(JiraPermission::isGranted).orElse(false);
    }
}
