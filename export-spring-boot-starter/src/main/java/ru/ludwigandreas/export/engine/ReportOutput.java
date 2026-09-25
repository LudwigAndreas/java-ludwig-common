package ru.ludwigandreas.export.engine;

import ru.ludwigandreas.export.api.StoredOutput;

/**
 * One file a run produced, as it was stored.
 *
 * <p>A run can produce several of these in one pass over the rows - see
 * {@link ExecutionPlan#outputs()} - so the format id is part of the identity rather than a property
 * of the run.
 *
 * @param formatId  which format, matching {@code ReportFormat.id()}
 * @param mediaType what the download endpoint reports. Not always the format's own: a multi-sheet
 *                  definition written as CSV under {@code ZIP} is an archive, and telling a browser
 *                  it is {@code text/csv} would make it try to display it
 * @param fileName  the name presented to a downloader, already sanitised and carrying the extension
 * @param stored    where the sink put it, how big it was and what it hashed to
 */
public record ReportOutput(String formatId, String mediaType, String fileName, StoredOutput stored) {

    public ReportOutput {
        if (formatId == null || formatId.isBlank()) {
            throw new IllegalArgumentException("A ReportOutput needs a format id");
        }
        if (stored == null) {
            throw new IllegalArgumentException("A ReportOutput needs its StoredOutput");
        }
    }
}
