package ru.ludwigandreas.export.format.xlsx;

import java.util.Collection;
import java.util.Locale;

/**
 * Turns a report's sheet title into a name a workbook will actually accept.
 *
 * <h2>The rules are Excel's, and they are not advisory</h2>
 *
 * <p>A sheet name may be at most 31 characters, may not contain any of {@code []:*?/\}, may not
 * begin or end with an apostrophe, and may not be empty. A workbook that violates any of them is
 * rejected by Excel on open - not repaired, rejected - so a report whose title happened to contain
 * a slash would produce a file nobody could read, with nothing in this service's logs to say why.
 *
 * <p>Truncation happens at the end rather than the middle because the distinguishing part of a
 * report title is almost always its start, and a name ending in an ellipsis reads as deliberate.
 *
 * <h2>Deduplication</h2>
 *
 * <p>Two sheets whose titles differ only past the 31st character collide after truncation, and POI
 * throws on the second. A numeric suffix is appended inside the limit, which is also how a
 * continuation sheet is named after a rollover - the two problems are the same problem, so they
 * share this code rather than each growing their own.
 */
public final class SheetNames {

    /** What a workbook allows. Not a preference: Excel refuses to open a file that exceeds it. */
    public static final int MAX_LENGTH = 31;

    private static final String ILLEGAL = "[]:*?/\\";

    private SheetNames() {
    }

    /**
     * A safe name for a title, unique among the names already used.
     *
     * @param title    the resolved sheet title
     * @param taken    names already in the workbook; the returned name is not one of them, and the
     *                 caller is expected to add it
     * @return a name of at most {@link #MAX_LENGTH} characters that a workbook will accept
     */
    public static String of(String title, Collection<String> taken) {
        String base = sanitize(title);
        if (!containsIgnoringCase(taken, base)) {
            return base;
        }
        for (int suffix = 2; suffix < Integer.MAX_VALUE; suffix++) {
            String candidate = withSuffix(base, " (" + suffix + ")");
            if (!containsIgnoringCase(taken, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not find a free sheet name for " + title);
    }

    /**
     * The name of a continuation sheet produced by a rollover.
     *
     * <p>Predictable on purpose: a recipient opening a workbook with four sheets called
     * {@code Orders}, {@code Orders (2)}, {@code Orders (3)} and {@code Orders (4)} can see at a
     * glance that they are one table, where names derived from row ranges would look like four
     * different reports.
     *
     * @param title the original sheet title
     * @param part  which continuation this is; the first continuation is 2
     * @param taken names already in the workbook
     * @return the continuation's name
     */
    public static String continuation(String title, int part, Collection<String> taken) {
        String candidate = withSuffix(sanitize(title), " (" + part + ")");
        return containsIgnoringCase(taken, candidate) ? of(candidate, taken) : candidate;
    }

    private static String sanitize(String title) {
        StringBuilder cleaned = new StringBuilder(MAX_LENGTH);
        for (int i = 0; i < title.length() && cleaned.length() < MAX_LENGTH; i++) {
            char c = title.charAt(i);
            if (ILLEGAL.indexOf(c) >= 0 || Character.isISOControl(c)) {
                continue;
            }
            cleaned.append(c);
        }
        while (cleaned.length() > 0 && cleaned.charAt(0) == '\'') {
            cleaned.deleteCharAt(0);
        }
        while (cleaned.length() > 0 && cleaned.charAt(cleaned.length() - 1) == '\'') {
            cleaned.deleteCharAt(cleaned.length() - 1);
        }
        return cleaned.length() == 0 ? "Sheet" : cleaned.toString();
    }

    private static String withSuffix(String base, String suffix) {
        int room = MAX_LENGTH - suffix.length();
        String head = base.length() > room ? base.substring(0, Math.max(1, room)) : base;
        return head + suffix;
    }

    /**
     * Excel compares sheet names case-insensitively, so {@code Orders} and {@code ORDERS} collide.
     * Checking the same way here is what keeps a workbook from being rejected on open by a
     * duplicate this code believed it had avoided.
     */
    private static boolean containsIgnoringCase(Collection<String> taken, String candidate) {
        String lowered = candidate.toLowerCase(Locale.ROOT);
        return taken.stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).equals(lowered));
    }
}
