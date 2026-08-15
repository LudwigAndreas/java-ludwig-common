package ru.ludwigandreas.hotreload.audit;

import java.util.regex.Pattern;

/**
 * Decides whether a changed value is sensitive enough to redact before it ever reaches a {@link
 * HotReloadAuditLogger} - deliberately applied at the point the audit entry is built ({@link
 * AuditingSourceChangeListener}), not left to each logger implementation, so a custom logger persisting
 * to a database or shipping to a SIEM can't forget to do it.
 *
 * <p>Two rules, either of which redacts: the key name matches a common secret-naming pattern (the same
 * kind of heuristic Spring Boot Actuator's own {@code Sanitizer} uses for {@code /actuator/env}), or the
 * value came from Vault at all - every Vault-sourced key is treated as sensitive regardless of its name,
 * since that's presumptively why it's in Vault.
 */
final class SecretRedaction {

    static final String REDACTED = "***REDACTED***";

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|secret|token|credential|passphrase|private.?key|api.?key).*");

    private SecretRedaction() {
    }

    static boolean isSensitive(String sourceId, String key) {
        return sourceId.startsWith("vault:") || sourceId.startsWith("vault-lease:")
                || SENSITIVE_KEY.matcher(key).matches();
    }

    static Object redactIfSensitive(String sourceId, String key, Object value) {
        if (value == null || !isSensitive(sourceId, key)) {
            return value;
        }
        return REDACTED;
    }
}
