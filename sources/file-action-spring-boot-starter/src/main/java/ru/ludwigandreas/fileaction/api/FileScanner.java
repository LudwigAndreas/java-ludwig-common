package ru.ludwigandreas.fileaction.api;

import java.io.InputStream;

/**
 * Decides whether a submitted file may be parsed at all.
 *
 * <h2>An SPI with no implementation in this module, on purpose</h2>
 *
 * <p>Which scanner an estate runs - an ICAP appliance, a {@code clamd} socket, a cloud scanning API - is
 * a deployment decision with its own credentials, network path and failure modes, and a scanner shipped
 * here would be one more thing to keep current than the security team already has. What the module owes
 * is the seam and the guarantee that a deployment cannot end up with no scanner <em>by accident</em>:
 * {@code ludwig.file-action.scanning.mode} defaults to {@code required}, so an application with no
 * {@link FileScanner} bean does not start, and the startup message names the property that disables the
 * requirement. Failing closed is the choice; a deployment is free to make the other one, out loud.
 *
 * <p>What this cannot check is whether an implementation actually scans. A bean returning
 * {@link ScanOutcome#safe} unconditionally satisfies every rule here, and only a person reviewing it
 * will notice. That gap is recorded in {@code docs/harness-enforcement.md} rather than left implied.
 */
public interface FileScanner {

    /**
     * Scans a submitted file.
     *
     * <p>Called after the bytes are stored and before anything parses them, which is the only ordering
     * that works: a scanner needs the whole file, and parsing before scanning would mean the parser - the
     * component most exposed to a crafted file - sees it first.
     *
     * <p>The stream is opened by the module from the stored object and closed by the module. An
     * implementation must read it rather than materialise it: a scanner that calls
     * {@code readAllBytes} reintroduces the heap cost the rest of the module is built to avoid, and the
     * ArchUnit rule that would catch it cannot see inside a consuming service's class.
     *
     * @param content  the stored file's bytes
     * @param filename the name the client sent, which some scanners use as a hint. Not to be trusted
     *                 for anything else
     * @param sizeBytes the file's size, already known from the stored object, so an implementation with
     *                  a size limit of its own need not count
     * @return what it concluded
     */
    ScanOutcome scan(InputStream content, String filename, long sizeBytes);

    /**
     * A name for this scanner, for the audit record.
     *
     * @return a short stable identifier, defaulting to the implementation's simple class name
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
