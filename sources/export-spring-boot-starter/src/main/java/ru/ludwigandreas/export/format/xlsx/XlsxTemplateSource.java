package ru.ludwigandreas.export.format.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Where a branding template workbook comes from.
 *
 * <p>An interface with one method, because the interesting decision is not how to open a file but
 * <em>what a name is allowed to resolve to</em>. A template is the letterhead of a document that
 * leaves the organisation, so an implementation that resolved a caller-supplied name against the
 * filesystem would be an arbitrary file read with a spreadsheet wrapped around the result. The
 * shipped implementation resolves only against a configured directory and the classpath, and
 * refuses anything that escapes either.
 */
@FunctionalInterface
public interface XlsxTemplateSource {

    /**
     * Opens the template with this name.
     *
     * @param name the template's name, from the report definition
     * @return the workbook bytes, or empty when no template of that name exists. Empty rather than
     *         an exception because a definition naming a template that has not been deployed yet
     *         should still produce a report - an unbranded file is a far better outcome than no
     *         file, and the omission is logged
     * @throws IOException if a template of that name exists and cannot be read
     */
    Optional<InputStream> open(String name) throws IOException;
}
