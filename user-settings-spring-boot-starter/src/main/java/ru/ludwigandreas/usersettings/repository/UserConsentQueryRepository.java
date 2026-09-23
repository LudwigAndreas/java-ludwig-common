package ru.ludwigandreas.usersettings.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;

/**
 * Reading the consent ledger.
 *
 * <p>There is no "current consent" table and no current-state column, because either would be a
 * second copy of a fact the history already holds - and the two would eventually disagree, at which
 * point neither could be trusted. Current state is the last decision in the ledger, computed on
 * read; a subject accumulates a handful of consent rows over their lifetime, so computing it is
 * cheaper than the machinery that keeping it in sync would need.
 */
public interface UserConsentQueryRepository {

    /**
     * The subject's consent decisions, oldest first.
     *
     * @param consentKey narrows to one consent; {@code null} returns every consent the subject has
     *                   a decision for
     * @param asOf       returns the ledger as it stood at that instant, which is how "prove what they
     *                   consented to on date X" is answered; {@code null} returns everything
     */
    List<UserConsentEntity> history(String tenantId, String subject, String consentKey, Instant asOf);

    /**
     * One keyset page of the ledger, for republication by the backfill.
     *
     * <p>Unlike {@link #history(String, String, String, Instant)} this is not scoped to a subject:
     * seeding a projection means seeding it for everybody, so the scan walks the table by primary
     * key. See {@code SettingsBackfillRequest} for why {@code tenantId} may be null here when it may
     * be null nowhere else.
     *
     * @param consentKeys restrict to these consents, or null/empty for all of them
     * @param afterId     resume after this id, or null to start from the beginning
     */
    List<UserConsentEntity> findForBackfill(String tenantId, Collection<String> consentKeys,
                                            UUID afterId, int limit);

    /**
     * Hard-deletes consent rows whose decision is older than {@code cutoff}, at most {@code batchSize}.
     *
     * <p>Deliberately never scheduled by this module. How long consent evidence has to be kept is a
     * legal question with a different answer in every jurisdiction and for every consent, and a
     * module that quietly deleted it on a timer would be destroying the evidence its whole design
     * exists to preserve. The method is here so that an operator acting on a retention policy has a
     * batched, index-served way to carry it out - not so that a default can.
     *
     * @return how many rows were removed; equal to {@code batchSize} means there is more to do
     */
    int purgeOlderThan(Instant cutoff, int batchSize);
}
