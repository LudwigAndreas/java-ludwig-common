package ru.ludwigandreas.usersettings.backfill;

/**
 * What one backfill run actually published.
 *
 * @param settingRows setting values republished
 * @param consentRows consent decisions republished
 * @param batches     how many transactions it took
 * @param complete    false when the run stopped because it hit
 *                    {@link SettingsBackfillRequest#maxRows()} rather than because it ran out of
 *                    rows. The caller is expected to run again; there is no cursor to carry, because
 *                    a re-run starts from the beginning and the projection drops everything it has
 *                    already applied as stale
 */
public record SettingsBackfillResult(long settingRows, long consentRows, int batches, boolean complete) {

    public static final SettingsBackfillResult EMPTY = new SettingsBackfillResult(0, 0, 0, true);

    public long totalRows() {
        return settingRows + consentRows;
    }
}
