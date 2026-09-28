package ru.ludwigandreas.usersettings.backfill;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Builder;

/**
 * What to republish, and how hard to push.
 *
 * <h2>Why a backfill exists at all</h2>
 *
 * <p>The change stream carries <em>changes</em>. A projection that is brought up after the owner has
 * been running for a while therefore starts empty and stays empty for every value nobody has touched
 * since - a user who set their timezone two years ago produces no event, so the new replica resolves
 * that setting to its definition default and is quietly wrong for exactly the people who configured
 * it most deliberately. Replaying the topic from the beginning fixes that only while the broker still
 * holds the whole history, which for a default retention it does not.
 *
 * <p>This request drives the other answer: read the owner's current state and publish it as ordinary
 * change events, so a projection reaches the right state through the only code path it has.
 *
 * <h2>The tenant is nullable here, and only here</h2>
 *
 * <p>Everywhere else in this module a missing tenant is refused outright, because an unscoped read is
 * a cross-tenant read waiting to happen. A backfill is the one operation whose unit of work is
 * legitimately the whole estate: rebuilding a replica means rebuilding it for everybody, and an
 * operator who had to enumerate tenants first would need a query this module deliberately does not
 * offer. A null {@code tenantId} therefore means every tenant, and
 * {@code SettingsBackfillController} never constructs one - an HTTP caller always gets their own
 * tenant pinned in, so the estate-wide form is reachable only from in-process operator code.
 *
 * @param tenantId        the tenant to republish, or null for every tenant
 * @param settingKeys     which settings to republish; empty means all of them. The usual reason to
 *                        narrow this is the case that motivates the whole feature - one new consumer
 *                        needs one setting it has never seen, and republishing the other forty would
 *                        be pointless traffic through every other projection as well
 * @param consentKeys     which consents to republish; empty means all of them
 * @param includeSettings whether to republish stored setting values
 * @param includeConsents whether to republish the consent ledger. On by default: a projection that
 *                        believes a user never consented is a compliance problem, not a stale cache
 * @param batchSize       rows per transaction. Each batch commits on its own, so a large backfill
 *                        never holds one long transaction open against tables that are also serving
 *                        writes
 * @param maxRows         a budget across the whole run, or 0 for no limit. A bounded run lets an
 *                        operator watch the first few thousand rows land before committing to the
 *                        rest, and lets an HTTP call return before anything times out. It is a
 *                        budget, not a cap: the scan stops after the batch that crosses it, so a run
 *                        can publish up to one batch more than asked.
 *                        <p><b>Shared across both scans, and settings are scanned first.</b> A budget
 *                        smaller than the settings table therefore never reaches the consents, however
 *                        many times it is re-run - the second run starts from the beginning too. Run
 *                        the two halves separately ({@code includeConsents(false)}, then
 *                        {@code includeSettings(false)}) whenever the budget is set at all
 */
@Builder
public record SettingsBackfillRequest(
        String tenantId,
        Set<String> settingKeys,
        Set<String> consentKeys,
        boolean includeSettings,
        boolean includeConsents,
        int batchSize,
        long maxRows) {

    /** Large enough that the per-batch overhead disappears, small enough to stay off the WAL's radar. */
    public static final int DEFAULT_BATCH_SIZE = 500;

    public SettingsBackfillRequest {
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "batchSize must be positive; a zero batch would loop forever without publishing anything");
        }
        if (maxRows < 0) {
            throw new IllegalArgumentException("maxRows cannot be negative; use 0 for no limit");
        }
        if (!includeSettings && !includeConsents) {
            throw new IllegalArgumentException(
                    "A backfill that republishes neither settings nor consents would do nothing");
        }
        settingKeys = copyOf(settingKeys);
        consentKeys = copyOf(consentKeys);
    }

    /** Everything, for one tenant - the shape an operator wants when adding a consumer. */
    public static SettingsBackfillRequest forTenant(String tenantId) {
        return builder().tenantId(tenantId).build();
    }

    /** One tenant, one setting - the shape an operator wants when adding a <em>setting</em>. */
    public static SettingsBackfillRequest forSettings(String tenantId, String... settingKeys) {
        return builder()
                .tenantId(tenantId)
                .settingKeys(Set.of(settingKeys))
                .includeConsents(false)
                .build();
    }

    private static Set<String> copyOf(Set<String> keys) {
        return keys == null || keys.isEmpty() ? Set.of() : Set.copyOf(new LinkedHashSet<>(keys));
    }

    /**
     * Hand-written so the two {@code include} flags default to true and the batch size to something
     * usable. Lombok's {@code @Builder.Default} would do the same, but it does it by generating a
     * field initializer the compact constructor above never sees, so a caller who went through the
     * canonical constructor instead would silently get {@code false} and an empty backfill.
     */
    public static class SettingsBackfillRequestBuilder {

        private boolean includeSettings = true;
        private boolean includeConsents = true;
        private int batchSize = DEFAULT_BATCH_SIZE;
    }
}
