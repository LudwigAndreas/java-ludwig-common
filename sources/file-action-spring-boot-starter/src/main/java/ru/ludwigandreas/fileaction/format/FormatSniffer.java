package ru.ludwigandreas.fileaction.format;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Decides what a submitted file actually is, from its first few bytes.
 *
 * <h2>The declared content type is a hint and nothing more</h2>
 *
 * <p>Users rename files. A CSV saved from a mail client arrives as {@code application/octet-stream}; a
 * genuine XLSX dragged out of a file manager sometimes arrives as {@code application/zip}, which it is;
 * and the single most common support case is a file somebody renamed to {@code .xlsx} because the import
 * "only accepts Excel". Choosing the parser from the declared type means handing a ZIP parser a text
 * file, whose error message is about a corrupt archive and tells the user nothing they can act on.
 *
 * <p>So the format comes from the content. The filename is kept for messages and the audit record only.
 *
 * <h2>Why this is not Apache Tika</h2>
 *
 * <p>Tika is the right answer when the set of possible types is open: it carries a curated signature
 * corpus that nobody here is going to maintain. This allow-list is closed and has two entries, and what
 * distinguishes them is four bytes of ZIP magic. Against that, {@code tika-core} would add a dependency
 * whose entire job is parsing untrusted bytes to the one module whose attack surface is untrusted bytes -
 * and the detection it would do for us is the dozen lines below.
 *
 * <p>Recorded here rather than only in the change's design, because the next person who wants more
 * formats will reach for Tika and should know this was considered and why it was declined.
 */
public final class FormatSniffer {

    /** Local file header of a ZIP entry - the bytes {@code 50 4B 03 04}. An XLSX is a ZIP. */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};

    /**
     * An empty ZIP's end-of-central-directory signature, the bytes {@code 50 4B 05 06}.
     *
     * <p>A workbook is never empty, so this is only ever seen on a file that is not one - but it is seen,
     * because some tools produce it, and matching it means the user is told the workbook has no sheets
     * rather than that the file is not a spreadsheet.
     */
    private static final byte[] EMPTY_ZIP_MAGIC = {0x50, 0x4B, 0x05, 0x06};

    /**
     * The OLE2 compound-document signature, which is what a legacy {@code .xls} starts with.
     *
     * <p>Detected rather than left to fall through to "unsupported", so that the user gets the one
     * message that helps - save it as {@code .xlsx} - rather than a generic refusal. A {@code .doc} and a
     * {@code .ppt} share this signature and get the same message, which is accurate for all three: none
     * of them is a format this module reads.
     */
    private static final byte[] OLE2_MAGIC = {
        (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
        (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1,
    };

    /** How many bytes are read to decide. The longest signature is eight; the decode probe wants more. */
    private static final int PROBE_BYTES = 4096;

    /** The longest UTF-8 sequence, minus one: how much of a full probe window may be a partial character. */
    private static final int MAX_PARTIAL_SEQUENCE = 3;

    /** The first printable ASCII code point; below it everything is a control character. */
    private static final int FIRST_PRINTABLE = 0x20;

    /**
     * Sniffs the format of a stream, leaving it positioned where it started.
     *
     * @param content  a stream supporting {@code mark} and {@code reset}; {@link #sniffing(InputStream)}
     *                 wraps one that does not
     * @param filename the name the client sent, for the refusal message only
     * @return the format
     * @throws FileRejectedException if the content is a legacy workbook, is empty, or is not a format
     *                               this module reads
     * @throws IOException if the stream cannot be read
     */
    public SourceFormat sniff(InputStream content, String filename) throws IOException {
        if (!content.markSupported()) {
            throw new IllegalArgumentException(
                    "FormatSniffer needs a stream it can reset, so the reader afterwards sees the file"
                            + " from byte zero. Use FormatSniffer.sniffing(stream)");
        }
        content.mark(PROBE_BYTES + 1);
        byte[] head = new byte[PROBE_BYTES];
        int read = content.readNBytes(head, 0, PROBE_BYTES);
        content.reset();

        if (read == 0) {
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.EMPTY,
                    filename);
        }
        if (startsWith(head, read, OLE2_MAGIC)) {
            throw new FileRejectedException(ProblemStatus.UNSUPPORTED_MEDIA_TYPE,
                    FileActionProblemCodes.LEGACY_XLS, filename);
        }
        if (startsWith(head, read, ZIP_MAGIC) || startsWith(head, read, EMPTY_ZIP_MAGIC)) {
            return SourceFormat.XLSX;
        }
        if (isProbablyText(head, read)) {
            return SourceFormat.CSV;
        }
        throw new FileRejectedException(ProblemStatus.UNSUPPORTED_MEDIA_TYPE,
                FileActionProblemCodes.UNSUPPORTED_FORMAT, filename);
    }

    /**
     * Wraps a stream so it can be sniffed and then read from the beginning.
     *
     * @param content any stream
     * @return a stream supporting {@code mark} over at least the probe window
     */
    public static InputStream sniffing(InputStream content) {
        return content.markSupported() ? content : new BufferedInputStream(content, PROBE_BYTES * 2);
    }

    private static boolean startsWith(byte[] head, int length, byte[] magic) {
        if (length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (head[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the probe decodes as UTF-8 text carrying no control characters a delimited file would not.
     *
     * <h2>Why a decode probe rather than a delimiter search</h2>
     *
     * <p>The obvious test - does the first line contain a comma or a semicolon - rejects a single-column
     * CSV, which is a legitimate file and one users submit. The question that actually separates a CSV
     * from arbitrary binary is whether the bytes are text at all.
     *
     * <p>The decode is deliberately strict and deliberately only UTF-8. A Windows-1251 file decodes under
     * UTF-8 only by accident, so a strict probe rejects most of them - which is the right outcome here,
     * because {@code CsvRowReader} reads UTF-8 and a code-page file would otherwise be accepted and then
     * produce mojibake in every cell. A refusal the user fixes by saving as UTF-8 beats an import of
     * garbled text that nobody notices until the orders are wrong.
     */
    private static boolean isProbablyText(byte[] head, int length) {
        // A probe window ending mid-character would fail to decode through no fault of the file, so the
        // last few bytes of a full window are dropped.
        int effective = length == PROBE_BYTES ? length - MAX_PARTIAL_SEQUENCE : length;
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(head, 0, effective));
        } catch (CharacterCodingException notText) {
            return false;
        }
        for (int i = 0; i < effective; i++) {
            byte b = head[i];
            boolean allowedControl = b == '\t' || b == '\n' || b == '\r';
            if (b >= 0 && b < FIRST_PRINTABLE && !allowedControl) {
                return false;
            }
        }
        return true;
    }
}
