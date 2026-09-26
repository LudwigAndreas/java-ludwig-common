package ru.ludwigandreas.audit.redaction;

import java.util.regex.Pattern;

/**
 * Sensitive because of what the key is called.
 *
 * <p>The heuristic from {@code hot-reload-spring-boot-starter}'s {@code SecretRedaction}, pattern
 * unchanged - the same kind of rule Spring Boot Actuator's own {@code Sanitizer} uses for
 * {@code /actuator/env}. It is the classifier that catches the secret nobody remembered to configure,
 * which is the only kind that ever leaks.
 *
 * <p>A heuristic rather than a list, and therefore neither complete nor precise: it will mask a
 * property called {@code token.refresh.interval} and it will miss one called {@code ldap_bind}. Both
 * are acceptable in that order. It is combined with, never substituted for, the configured-list and
 * declaration classifiers - see {@link SensitivityClassifier#anyOf}.
 */
public final class KeyNameSensitivityClassifier implements SensitivityClassifier {

    /** The shipped pattern, matched case-insensitively against the whole key. */
    public static final Pattern DEFAULT_PATTERN = Pattern.compile(
            "(?i).*(password|secret|token|credential|passphrase|private.?key|api.?key).*");

    private final Pattern pattern;

    /** A classifier over {@link #DEFAULT_PATTERN}. */
    public KeyNameSensitivityClassifier() {
        this(DEFAULT_PATTERN);
    }

    /**
     * A classifier over a deployment's own pattern.
     *
     * <p>The pattern is configurable where the mask is not, and the asymmetry is deliberate: widening
     * what counts as a secret is always safe, whereas a deployment that could change the mask could
     * set it to the empty string - see {@link Redaction}.
     *
     * @param pattern matched against the whole key; null falls back to {@link #DEFAULT_PATTERN}
     */
    public KeyNameSensitivityClassifier(Pattern pattern) {
        this.pattern = pattern == null ? DEFAULT_PATTERN : pattern;
    }

    @Override
    public boolean isSensitive(String provenance, String key) {
        return key != null && pattern.matcher(key).matches();
    }
}
