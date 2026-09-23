package ru.ludwigandreas.usersettings.consent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where a subject's consents stand, derived from the ledger rather than stored.
 *
 * <p>There is no current-state table and no "is granted" column. Either would be a second copy of a
 * fact the history already holds, and the two would eventually disagree - after a failed migration,
 * a partial projection, a manual correction - at which point neither could be trusted, which is a
 * bad position to be in about consent specifically. A subject accumulates a handful of decisions
 * over their lifetime, so folding them is cheaper than the machinery that keeping a summary in step
 * would need.
 *
 * <p>Obtained for "now" or for any past instant, which is what makes "prove what they had consented
 * to on the day we sent that email" a query rather than an archaeology exercise.
 */
public final class ConsentState {

    private final Map<String, ConsentRecord> latestByKey;

    public ConsentState(Map<String, ConsentRecord> latestByKey) {
        this.latestByKey = Collections.unmodifiableMap(new LinkedHashMap<>(latestByKey));
    }

    public static ConsentState empty() {
        return new ConsentState(Map.of());
    }

    /**
     * Whether the subject currently consents.
     *
     * <p>False when they never decided at all, and false when their last decision was a revocation.
     * Collapsing "never asked" and "said no" is deliberate here: both mean "you do not have
     * permission", and a caller that needs to tell them apart - to decide whether to ask - reads
     * {@link #latest(String)} instead.
     */
    public boolean isGranted(String consentKey) {
        return latest(consentKey).map(ConsentRecord::isGranted).orElse(false);
    }

    /**
     * Whether the subject consents to a specific version of the text.
     *
     * <p>The question to ask when the wording has changed and the old agreement no longer covers
     * what is being done. A grant against version 3 does not answer for version 4, and treating it
     * as though it did is exactly the failure that recording the version exists to prevent.
     */
    public boolean isGrantedForVersion(String consentKey, String textVersion) {
        return latest(consentKey)
                .filter(ConsentRecord::isGranted)
                .map(record -> record.textVersion().equals(textVersion))
                .orElse(false);
    }

    /** The most recent decision for one consent, granted or revoked. */
    public Optional<ConsentRecord> latest(String consentKey) {
        return Optional.ofNullable(latestByKey.get(consentKey));
    }

    /** The most recent decision for every consent the subject has ever decided on. */
    public Map<String, ConsentRecord> all() {
        return latestByKey;
    }

    public boolean isEmpty() {
        return latestByKey.isEmpty();
    }
}
