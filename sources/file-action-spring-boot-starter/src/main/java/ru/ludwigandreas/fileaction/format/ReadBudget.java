package ru.ludwigandreas.fileaction.format;

import java.time.Duration;

/**
 * What a reader is allowed to spend on one submission.
 *
 * <h2>Why the budget is passed in rather than read from configuration by the reader</h2>
 *
 * <p>The effective budget is per action, resolved from the action's own settings falling back to the
 * module defaults, and that resolution happens once, at startup. A reader that read configuration itself
 * would resolve it per file, would have to know about the action, and - the reason that matters - could
 * not be handed a deliberately tiny budget by a test. Every ceiling in this record is exercised by a test
 * that sets it to something small, which is only possible because it is an argument.
 *
 * @param maxBytes              the largest submission the action accepts. Checked before the content is
 *                              opened and again while it is read, because a declared length can lie
 * @param maxRows               the most data rows read. On exceeding it the read stops and the
 *                              submission is rejected: continuing would make the ceiling advisory
 * @param maxArchiveEntries     the most entries a ZIP container may hold. An XLSX of a few sheets has
 *                              tens; a bomb has tens of thousands
 * @param minInflateRatio       the least compressed-to-uncompressed ratio tolerated, as POI's
 *                              {@code ZipSecureFile} means it. A legitimate workbook's XML compresses
 *                              perhaps ten to one; a bomb compresses a thousand to one
 * @param maxUncompressedBytes  the most a container may inflate to in total, which is the ceiling that
 *                              catches a bomb spread thinly across many entries so that no single
 *                              entry's ratio looks alarming
 * @param maxSharedStringBytes  the most the shared-strings table may occupy. POI materialises it, so it
 *                              is the one part of a streaming XLSX read whose cost is not bounded by the
 *                              row window
 * @param maxCellCharacters     the most characters one cell may hold. A single cell carrying a megabyte
 *                              of text is not a spreadsheet and is a cheap way to make a reject report
 *                              enormous
 * @param readTimeout           how long one read may take. A file crafted to be pathologically slow
 *                              rather than large is a denial of service that no size ceiling catches
 */
public record ReadBudget(long maxBytes, int maxRows, int maxArchiveEntries, double minInflateRatio,
                         long maxUncompressedBytes, long maxSharedStringBytes, int maxCellCharacters,
                         Duration readTimeout) {

    /** Rejects a budget with a non-positive ceiling, which would refuse every file or no file. */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor; every component is a
    // separate ceiling and collapsing any two of them would make one of them unsettable.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReadBudget {
        positive("maxBytes", maxBytes);
        positive("maxRows", maxRows);
        positive("maxArchiveEntries", maxArchiveEntries);
        positive("maxUncompressedBytes", maxUncompressedBytes);
        positive("maxSharedStringBytes", maxSharedStringBytes);
        positive("maxCellCharacters", maxCellCharacters);
        if (minInflateRatio <= 0 || minInflateRatio >= 1) {
            throw new IllegalArgumentException(
                    "minInflateRatio is a compressed-to-uncompressed fraction and must be between 0 and"
                            + " 1 exclusive, was " + minInflateRatio);
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("readTimeout must be positive, was " + readTimeout);
        }
    }

    private static void positive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
