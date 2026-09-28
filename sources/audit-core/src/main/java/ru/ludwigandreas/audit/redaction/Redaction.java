package ru.ludwigandreas.audit.redaction;

/**
 * What a sensitive value looks like everywhere outside the store that owns it.
 *
 * <p>One marker for the whole platform. There were three - {@code ***REDACTED***} in the hot-reload
 * module, {@code ****} in the REST client, {@code [redacted]} in user settings - and one of them was
 * not only logged but <em>persisted</em>, into {@code user_setting_audit.old_value} and
 * {@code new_value}. Picking one was therefore a data migration and not a constant change: without
 * migrating those rows the table would spell the same concept two ways, and no query could tell "a
 * value redacted under the old rule" from "a user whose setting value is literally the string
 * {@code [redacted]}". The migration is {@code audit-005} in this platform's audit changelog.
 *
 * <h2>Why a fixed marker and not a hash or a truncation</h2>
 *
 * <p>Inherited verbatim from {@code SettingsRedaction}, because the argument is the best statement of
 * the decision in this repository and none of it stopped being true. A hash is reversible for any
 * value drawn from a small set - which most settings are, and a phone number certainly is - and a
 * truncation leaks exactly the part of an identifier that identifies. Neither is worth the
 * debuggability it buys, because the audit event already records which thing changed, when, and by
 * whom, and that is what the trail is read for.
 *
 * <h2>Why it is not configurable</h2>
 *
 * <p>Also inherited, also still true. A deployment that could change the marker could set it to the
 * empty string, at which point a redacted value and a value that was never set become the same row.
 * The mask is a {@code static final} on this class and there is deliberately no property that moves
 * it.
 */
public final class Redaction {

    /**
     * Stored, logged and published in place of a sensitive value.
     *
     * <p>{@code ***REDACTED***} rather than one of the other two: it is unmistakably a marker at a
     * glance, which {@code ****} is not - a four-star mask is indistinguishable from a value somebody
     * masked by hand, and from a four-character password.
     */
    // SUPPRESS CHECKSTYLE ID SecondRedactionMask - this declaration IS the platform's one mask; the
    // rule exists to stop a second one appearing anywhere else.
    public static final String MASK = "***REDACTED***";

    /**
     * The marker {@code user-settings-spring-boot-starter} persisted before the consolidation.
     *
     * <p>Kept as a constant, and not only in the changelog, because the migration is verified by a
     * test that has to name the string it is migrating away from. Nothing writes it.
     */
    // SUPPRESS CHECKSTYLE ID SecondRedactionMask - the marker being migrated away from, named here
    // because the migration test has to spell out the string it is migrating. Nothing writes it.
    public static final String LEGACY_SETTINGS_MASK = "[redacted]";

    /**
     * The marker {@code rest-client-spring-boot-starter} used in log lines and exception messages.
     *
     * <p>Never persisted anywhere, so it needs no migration - but a deployment grepping its REST
     * client logs for it will stop finding it, which that module's README says.
     */
    // SUPPRESS CHECKSTYLE ID SecondRedactionMask - a retired marker, kept only so a reader can find out
    // what a deployment's old REST client logs contain. Nothing writes it.
    public static final String LEGACY_HEADER_MASK = "****";

    private Redaction() {
    }

    /** Whether {@code value} is a redaction marker, this platform's or one of the two it replaced. */
    public static boolean isMask(String value) {
        return MASK.equals(value) || LEGACY_SETTINGS_MASK.equals(value)
                || LEGACY_HEADER_MASK.equals(value);
    }
}
