package ru.ludwigandreas.export.engine;

import java.util.Locale;
import java.util.UUID;

/**
 * Names a downloaded file so that it survives the trip to the recipient's machine.
 *
 * <h2>What gets removed, and what deliberately does not</h2>
 *
 * <p>Path separators, control characters and the characters Windows reserves are removed, because a
 * file name is written to a filesystem at both ends and any of them turns a download into a failure
 * or, worse, into a write somewhere unintended. A leading dot is removed for the same reason.
 *
 * <p>Non-ASCII characters are <em>not</em> removed. A report titled in Russian should download with
 * a Russian name; transliterating or stripping would produce a folder of files nobody can tell
 * apart. Getting that name through HTTP is the {@code Content-Disposition} header's problem, and it
 * is solved there with {@code filename*=UTF-8''...} rather than here by mangling the name.
 *
 * <p>The run id is appended because two runs of the same report on the same day are the normal case,
 * and a browser that silently renames the second to {@code report (1).xlsx} has made the file
 * impossible to match against the run that produced it - which is exactly what an auditor asks for.
 */
public final class ReportFileNames {

    /** Characters no filesystem this platform targets will accept in a name. */
    private static final String ILLEGAL = "/\\\\:*?\"<>|";

    /** How much of the run id is appended; enough to distinguish runs, short enough to read. */
    private static final int RUN_ID_CHARS = 8;

    /** Keeps the whole name inside the 255-byte limit that most filesystems impose. */
    private static final int MAX_TITLE_CHARS = 120;

    private ReportFileNames() {
    }

    /**
     * Builds the download name.
     *
     * @param title     the report's title, already resolved into the run's locale
     * @param runId     the run, whose leading characters disambiguate repeated runs
     * @param extension the file extension, without a dot
     * @return a name that is safe to write and still recognisable
     */
    public static String of(String title, UUID runId, String extension) {
        String cleaned = sanitize(title);
        String base = cleaned.isBlank() ? "report" : cleaned;
        String suffix = runId.toString().substring(0, RUN_ID_CHARS);
        return base + "-" + suffix + "." + extension;
    }

    private static String sanitize(String title) {
        StringBuilder cleaned = new StringBuilder(Math.min(title.length(), MAX_TITLE_CHARS));
        for (int i = 0; i < title.length() && cleaned.length() < MAX_TITLE_CHARS; i++) {
            char c = title.charAt(i);
            if (Character.isISOControl(c) || ILLEGAL.indexOf(c) >= 0) {
                continue;
            }
            cleaned.append(Character.isWhitespace(c) ? '-' : c);
        }
        while (cleaned.length() > 0 && (cleaned.charAt(0) == '.' || cleaned.charAt(0) == '-')) {
            cleaned.deleteCharAt(0);
        }
        while (cleaned.length() > 0 && cleaned.charAt(cleaned.length() - 1) == '.') {
            cleaned.deleteCharAt(cleaned.length() - 1);
        }
        return cleaned.toString().toLowerCase(Locale.ROOT);
    }
}
