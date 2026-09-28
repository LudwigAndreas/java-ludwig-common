package ru.ludwigandreas.export.api;

import java.io.IOException;
import java.util.Set;

/**
 * Creates the writer for one format. Registering one as a bean is how a service adds a format.
 *
 * <p>This is the module's intended extension point, which is why {@link ReportFormat} is open where
 * {@link Enricher} and {@link CellValue} are sealed. A new factory bean for a new format needs no
 * change to the engine, the registry or the web layer: the engine dispatches on
 * {@link #format()}, and the startup validator learns what the format can carry from
 * {@link ReportFormat#capabilities()}.
 */
public interface ReportWriterFactory {

    /**
     * The format this factory writes.
     *
     * @return the format, whose id must be unique across the registered factories
     */
    ReportFormat format();

    /**
     * Per-format option names this factory understands.
     *
     * <p>Requests carrying an option outside this set are rejected with a localized 400 naming the
     * options that are accepted, rather than having it ignored. An ignored option is the failure
     * that costs the most to diagnose: the requester asked for a semicolon delimiter, received
     * commas, and has no way to tell whether the option was misspelled, unsupported, or overridden.
     *
     * @return the accepted option names; empty means this format takes none
     */
    default Set<String> supportedOptions() {
        return Set.of();
    }

    /**
     * Creates a writer for one run.
     *
     * @param context the run's target file, locale, timezone, metadata and validated options
     * @return a writer positioned before its first sheet
     * @throws IOException if the target file cannot be opened
     */
    ReportWriter create(WriterContext context) throws IOException;
}
