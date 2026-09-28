package ru.ludwigandreas.usersettings.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;

/**
 * The QueryDSL half of the settings repository: every query that is not a plain lookup by id.
 */
public interface UserSettingValueQueryRepository {

    /**
     * Every stored value for any of these scopes, within one tenant, in <b>one</b> query.
     *
     * <p>This is the method the whole module's performance rests on. A settings page or a
     * notification fan-out resolves a subject once, and the scopes it needs - the user, each of their
     * roles, their tenant - are known before anything is fetched, so they go into a single predicate
     * instead of a query per layer. The alternative shape, one query per scope, is the N+1 that would
     * make this module slower than the per-service tables it replaces.
     *
     * <p>Scoped to {@code tenantId} with a plain equality, not a filter applied afterwards: a row
     * belonging to another tenant is never fetched, so there is no point at which a mistake could
     * let one through.
     */
    List<UserSettingValueEntity> loadForScopes(String tenantId, List<SettingScope> scopes);

    /**
     * One row at one scope, tombstone included.
     *
     * <p>The only query that does <em>not</em> filter tombstones out, because it serves the write
     * path: setting a value that was previously reset has to find the tombstone and revive it, and a
     * query that hid it would insert a second row and hit the unique constraint instead.
     */
    Optional<UserSettingValueEntity> findValue(String tenantId, SettingScope scope, String settingKey);

    /** Every live value stored at one scope - what an administrative screen lists. */
    List<UserSettingValueEntity> findByScope(String tenantId, SettingScope scope);

    /**
     * One keyset page of stored rows, for republication by the backfill.
     *
     * <p><b>Tombstones are included, deliberately.</b> A removal is a fact the projection needs as
     * much as a value is: a replica that was seeded with only the live rows, having previously been
     * told about a value that was later reset, would keep serving the resurrected value forever.
     *
     * <p>Ordered by primary key and resumed with {@code afterId} rather than paged with an offset,
     * because an offset scan over a large table re-walks everything it has already skipped and loses
     * rows outright when a concurrent write shifts the ordering underneath it.
     *
     * @param tenantId    the tenant to read, or null for every tenant - the one deliberately
     *                    unscoped query in this module; see {@code SettingsBackfillRequest}
     * @param settingKeys restrict to these keys, or null/empty for all of them
     * @param afterId     resume after this id, or null to start from the beginning
     */
    List<UserSettingValueEntity> findForBackfill(String tenantId, Collection<String> settingKeys,
                                                 UUID afterId, int limit);

    /**
     * Hard-deletes tombstones whose removal is older than {@code cutoff}, at most {@code batchSize}.
     *
     * <p>A tombstone only has to outlive the window in which a late event could still arrive for it.
     * Keeping them forever would mean a row per setting a user has ever reset, permanently, which is
     * a slow leak rather than a bug but is still a leak.
     *
     * @return how many rows were removed; equal to {@code batchSize} means there is more to do
     */
    int purgeTombstonesOlderThan(java.time.Instant cutoff, int batchSize);
}
