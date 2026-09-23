package ru.ludwigandreas.usersettings.repository;

import java.time.Instant;
import java.util.List;
import ru.ludwigandreas.usersettings.entity.UserSettingAuditEntity;

/** Reading and pruning the change trail. */
public interface UserSettingAuditQueryRepository {

    /** The trail for one subject, newest first, bounded so a long-lived account cannot return a page of thousands. */
    List<UserSettingAuditEntity> forSubject(String tenantId, String subject, int limit);

    /**
     * Deletes entries older than {@code cutoff}, at most {@code batchSize} of them.
     *
     * <p>Batched rather than one statement for the whole backlog. A single unbounded delete on a
     * table that has been accumulating for a year takes a long lock and a large amount of WAL, on a
     * table that is also on the write path of every settings change - so the purge runs in bounded
     * chunks and simply comes back for the rest.
     *
     * @return how many rows were removed; equal to {@code batchSize} means there is more to do
     */
    int purgeOlderThan(Instant cutoff, int batchSize);
}
