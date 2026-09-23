package ru.ludwigandreas.usersettings.consent;

import java.time.Instant;
import java.util.List;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * Recording and reading consent decisions.
 *
 * <p>Separate from {@code SettingsWriter} because consents are not settings, however similar they
 * look from an API. A setting is current state and is meant to be overwritten; a consent is evidence
 * and is meant to accumulate. Giving them one interface would have meant one storage model, and the
 * storage model that suits a setting - upsert in place, last write wins - destroys the only thing a
 * consent is for.
 *
 * <p>Available in owner mode. A projection consumes consent events into the same ledger and exposes
 * the read half; the write methods are registered only where the data is owned.
 */
public interface ConsentService {

    /**
     * Records agreement to a specific version of a consent text.
     *
     * <p>Always an insert. Granting twice records two rows, and that is correct - the second grant
     * is a fact that happened, possibly against a newer version of the text.
     */
    ConsentRecord grant(PrincipalRef ref, ConsentGrant grant);

    /**
     * Records withdrawal. A new row with {@code decision = REVOKED}, never an update of the grant.
     *
     * @param grant carries the version of the text being withdrawn from, so the ledger records what
     *              the subject believed they were revoking
     */
    ConsentRecord revoke(PrincipalRef ref, ConsentGrant grant);

    /** Where the subject's consents stand right now. */
    ConsentState currentState(PrincipalRef ref);

    /** As above, with the tenant named explicitly rather than taken from the security context. */
    ConsentState currentState(SettingsSubject subject);

    /**
     * Where they stood at a past instant - the answer to "prove what they consented to on date X".
     */
    ConsentState stateAsOf(PrincipalRef ref, Instant asOf);

    /** As above, with the tenant named explicitly rather than taken from the security context. */
    ConsentState stateAsOf(SettingsSubject subject, Instant asOf);

    /**
     * Every decision for one consent, oldest first.
     *
     * @param consentKey {@code null} for the subject's whole ledger
     */
    List<ConsentRecord> history(PrincipalRef ref, String consentKey);

    /** As above, with the tenant named explicitly rather than taken from the security context. */
    List<ConsentRecord> history(SettingsSubject subject, String consentKey);
}
