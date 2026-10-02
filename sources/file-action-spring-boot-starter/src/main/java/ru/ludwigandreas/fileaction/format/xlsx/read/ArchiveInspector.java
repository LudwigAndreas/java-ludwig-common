package ru.ludwigandreas.fileaction.format.xlsx.read;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.format.ReadBudget;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Checks a workbook's ZIP container against the budget before any XML is parsed.
 *
 * <h2>Why this runs before the parser, not inside it</h2>
 *
 * <p>An XLSX is a ZIP of XML, chosen by a user. The cheapest denial of service against any such format is
 * a bomb: a few kilobytes that inflate to gigabytes. By the time a SAX handler notices, the inflater has
 * already produced the bytes. Everything here is read from the ZIP's <em>central directory</em>, which
 * carries each entry's compressed and uncompressed size, so the decision is made from metadata and
 * nothing is decompressed to make it.
 *
 * <h2>Why not just POI's ZipSecureFile</h2>
 *
 * <p>POI has a guard, and it is used as well - see {@code XlsxReadDefaults} - but it is configured through
 * {@code ZipSecureFile.setMinInflateRatio}, which is a <strong>JVM-global static</strong>. That makes it
 * unusable as a per-action ceiling: this module's budget is per action, and {@code export} shares the JVM
 * and reads administrator-supplied templates under different assumptions. So the per-action ceilings are
 * enforced here, where they can differ per submission, and POI's global is left set to a conservative
 * backstop for anything that gets past this.
 *
 * <p>The limitation of reading the directory is that the sizes in it are written by whoever produced the
 * file and can lie. That is exactly what POI's global backstop is for: it measures actual inflation as it
 * happens. Two mechanisms, one cheap and precise and one expensive and honest, and the reason both exist
 * is written here so neither gets removed as redundant.
 */
public final class ArchiveInspector {

    /** The directory prefix a workbook's external-link parts live under. */
    private static final String EXTERNAL_LINKS_PREFIX = "xl/externalLinks/";

    /** Where the shared-strings table lives, whose size is the one unbounded cost of a streaming read. */
    private static final String SHARED_STRINGS_ENTRY = "xl/sharedStrings.xml";

    /**
     * Inspects a workbook container.
     *
     * @param workbook the local file
     * @param budget   the ceilings
     * @throws FileRejectedException if the container exceeds a ceiling, references external data, or
     *                               cannot be read as a ZIP at all
     */
    public void inspect(Path workbook, ReadBudget budget) {
        try (ZipFile zip = new ZipFile(workbook.toFile())) {
            long totalUncompressed = 0;
            int entries = 0;
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                entries++;
                if (entries > budget.maxArchiveEntries()) {
                    throw new FileRejectedException(ProblemStatus.PAYLOAD_TOO_LARGE,
                            FileActionProblemCodes.SUSPICIOUS_ARCHIVE, budget.maxArchiveEntries());
                }
                if (entry.getName().startsWith(EXTERNAL_LINKS_PREFIX)) {
                    // Refused rather than ignored. A workbook whose values come from another file would
                    // import whatever POI last cached for those cells, which is either stale or empty and
                    // in both cases is not what the user is looking at on their screen. Telling them to
                    // paste the values is the only honest outcome.
                    throw new FileRejectedException(ProblemStatus.INVALID,
                            FileActionProblemCodes.EXTERNAL_REFERENCES, entry.getName());
                }
                long uncompressed = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (uncompressed < 0) {
                    // The directory does not say. Nothing can be concluded from an unknown size, so this
                    // entry is left to POI's global backstop rather than guessed at.
                    continue;
                }
                totalUncompressed += uncompressed;
                if (totalUncompressed > budget.maxUncompressedBytes()) {
                    throw new FileRejectedException(ProblemStatus.PAYLOAD_TOO_LARGE,
                            FileActionProblemCodes.SUSPICIOUS_ARCHIVE, budget.maxUncompressedBytes());
                }
                if (compressed > 0 && uncompressed > 0) {
                    double ratio = (double) compressed / (double) uncompressed;
                    if (ratio < budget.minInflateRatio()) {
                        throw new FileRejectedException(ProblemStatus.PAYLOAD_TOO_LARGE,
                                FileActionProblemCodes.SUSPICIOUS_ARCHIVE, entry.getName());
                    }
                }
                if (SHARED_STRINGS_ENTRY.equals(entry.getName())
                        && uncompressed > budget.maxSharedStringBytes()) {
                    // The shared-strings table is the one part POI materialises whatever the reader does,
                    // so its size is the real memory ceiling of an XLSX read and is checked separately
                    // from the total.
                    throw new FileRejectedException(ProblemStatus.PAYLOAD_TOO_LARGE,
                            FileActionProblemCodes.SUSPICIOUS_ARCHIVE, budget.maxSharedStringBytes());
                }
            }
            if (entries == 0) {
                throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.EMPTY,
                        workbook.getFileName().toString());
            }
        } catch (IOException notAZip) {
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.UNREADABLE,
                    notAZip, "xlsx");
        }
    }
}
