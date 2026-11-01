package ru.ludwigandreas.identity.directory.repository;

import java.time.Instant;

/**
 * How far a directory-fed source has been read, and how its last sync and sweep ended.
 *
 * @param source            the source
 * @param watermark         where the next incremental sync starts, or null before the first sweep
 * @param lastSyncAt        when the last sync ended
 * @param lastSyncOutcome   {@code succeeded} or {@code failed}
 * @param lastSyncError     the failure's type, never its message
 * @param lastSyncPages     pages the last sync read
 * @param lastSyncChanges   changes the last sync applied
 * @param lastSweepAt       when the last sweep ended
 * @param lastSweepOutcome  {@code succeeded}, {@code aborted} or {@code failed}
 * @param lastSweepError    the failure's type, never its message
 * @param lastSweepPages    pages the last sweep read
 * @param lastSweepDisabled users the last sweep disabled
 */
public record DirectoryState(String source, Instant watermark, Instant lastSyncAt, String lastSyncOutcome,
                             String lastSyncError, Integer lastSyncPages, Integer lastSyncChanges,
                             Instant lastSweepAt, String lastSweepOutcome, String lastSweepError,
                             Integer lastSweepPages, Integer lastSweepDisabled) {
}
