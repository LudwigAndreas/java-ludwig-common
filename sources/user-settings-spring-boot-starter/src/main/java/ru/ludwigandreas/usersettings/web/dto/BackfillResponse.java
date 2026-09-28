package ru.ludwigandreas.usersettings.web.dto;

/**
 * What a backfill run published.
 *
 * @param settingRows setting values republished
 * @param consentRows consent decisions republished
 * @param batches     transactions it took
 * @param complete    false when the run stopped at {@code maxRows}; call again to continue. There is
 *                    no cursor to pass back, because a second run simply starts from the beginning
 *                    and the projections drop everything they already hold
 */
public record BackfillResponse(long settingRows, long consentRows, int batches, boolean complete) {
}
