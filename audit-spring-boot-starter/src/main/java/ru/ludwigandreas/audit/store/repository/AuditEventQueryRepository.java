package ru.ludwigandreas.audit.store.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.store.entity.AuditEventEntity;

/**
 * Reading and pruning the trail.
 *
 * <p>Separate from the write side on purpose. {@code AuditSink} has one method and cannot be asked
 * questions, because "a trail that the thing being audited also reads is a trail that will eventually
 * be filtered by it" - so the read side is a different type with a different set of callers, wired for
 * the administrative and retention paths rather than handed to every module that emits an event.
 */
public interface AuditEventQueryRepository {

    /**
     * The events matching {@code query}, newest first.
     *
     * @param query the criteria
     * @return the events, at most {@code query.limit()} of them
     */
    List<AuditEventEntity> find(AuditTrailQuery query);

    /**
     * How many events in {@code category} are older than {@code cutoff}.
     *
     * <p>What the purge reports having removed, counted before it removes anything, so the
     * {@code audit.purged} event says what the window actually contained rather than what one batch
     * happened to reach.
     *
     * @param category the category, or {@code null} for every category
     * @param cutoff   the age boundary
     * @return the count
     */
    long countOlderThan(String category, Instant cutoff);

    /**
     * How many events <em>outside</em> {@code excludedCategories} are older than {@code cutoff}.
     *
     * @param excludedCategories the categories that have a retention period of their own
     * @param cutoff             the age boundary
     * @return the count
     */
    long countOlderThanExcluding(Collection<String> excludedCategories, Instant cutoff);

    /**
     * Deletes events <em>outside</em> {@code excludedCategories} older than {@code cutoff}.
     *
     * <p>The complement of {@link #purgeOlderThan}, and the reason it exists rather than the purge walking a
     * list of known categories: {@link AuditEvent#category()} is deliberately open, so a service audits its
     * own domain under a category this module has never heard of. A purge that enumerated the platform's own
     * nine categories would silently never expire those rows - a retention hole that appears only once a
     * deployment configures its first per-category override, which is exactly when nobody is looking for one.
     *
     * @param excludedCategories the categories that have a retention period of their own
     * @param cutoff             the age boundary
     * @param batchSize          the most rows to remove in this call
     * @return how many rows were removed; equal to {@code batchSize} means there is more to do
     */
    int purgeOlderThanExcluding(Collection<String> excludedCategories, Instant cutoff, int batchSize);

    /**
     * Deletes events in {@code category} older than {@code cutoff}, at most {@code batchSize} of them.
     *
     * <p>Batched rather than one statement for the whole backlog, for the reason
     * {@code UserSettingAuditQueryRepository} gave: a single unbounded delete on a table that has been
     * accumulating for a year takes a long lock and a large amount of WAL, on a table that is also on
     * the write path of every audited operation - so the purge runs in bounded chunks and simply comes
     * back for the rest.
     *
     * @param category  the category, or {@code null} for every category
     * @param cutoff    the age boundary
     * @param batchSize the most rows to remove in this call
     * @return how many rows were removed; equal to {@code batchSize} means there is more to do
     */
    int purgeOlderThan(String category, Instant cutoff, int batchSize);
}
