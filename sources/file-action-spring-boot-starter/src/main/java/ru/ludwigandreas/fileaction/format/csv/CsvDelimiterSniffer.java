package ru.ludwigandreas.fileaction.format.csv;

/**
 * Works out a CSV's delimiter from its first line.
 *
 * <h2>The algorithm, and why it is this one</h2>
 *
 * <p>Count each candidate delimiter's occurrences in the first line, ignoring any inside quotes, and take
 * the most frequent. Ties go to the earlier entry of {@link CsvDelimiter}, which puts RFC 4180's comma
 * first.
 *
 * <p>Two things it deliberately does not do. It does not look past the first line: the header line is the
 * one guaranteed to have every delimiter and no embedded newlines, and a sniffer that read further would
 * need to buffer an unbounded amount to find the end of a quoted field. And it does not require a minimum
 * count, so a single-column file - zero delimiters everywhere - sniffs as a comma and reads correctly,
 * which is the case a "must contain a delimiter" check gets wrong.
 *
 * <p>Quote-awareness is not decoration. A single-column file whose header is {@code "Name, including
 * title"} contains a comma inside quotes and nothing else; counting it naively makes a comma the winner,
 * which is also the right answer here by luck - but the same file with a semicolon in the quoted header
 * would be sniffed as semicolon-delimited and every row would be split in the wrong place.
 */
final class CsvDelimiterSniffer {

    private CsvDelimiterSniffer() {
    }

    /**
     * Sniffs the delimiter of a header line.
     *
     * @param headerLine the first line of the file, with any byte-order mark already removed
     * @return the delimiter; {@link CsvDelimiter#COMMA} when nothing in the line distinguishes them
     */
    static CsvDelimiter sniff(String headerLine) {
        CsvDelimiter best = CsvDelimiter.COMMA;
        int bestCount = -1;
        for (CsvDelimiter candidate : CsvDelimiter.values()) {
            int count = countOutsideQuotes(headerLine, candidate.character());
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    private static int countOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean inQuotes = false;
        // A while loop rather than a for: scanning a doubled quote consumes two characters, and advancing
        // a for loop's control variable inside its body is both unreadable and a Checkstyle failure.
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"') {
                boolean escaped = inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"';
                if (escaped) {
                    // A doubled quote inside a quoted field is an escaped quote and does not end it.
                    i += 2;
                    continue;
                }
                inQuotes = !inQuotes;
            } else if (c == delimiter && !inQuotes) {
                count++;
            }
            i++;
        }
        return count;
    }
}
