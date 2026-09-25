package ru.ludwigandreas.export.format.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportWriter;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.api.WriterContext;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.i18n.ExportMessages;

/**
 * Creates XLSX writers, reading the configuration so the writer does not have to.
 *
 * <p>Takes no per-request options at all, deliberately. The things a request could plausibly want to
 * vary here - the flush window, whether the header freezes, whether the metadata sheet is written -
 * are operational settings or provenance, and neither belongs to whoever pressed the button. A
 * request that could switch off the metadata sheet could produce a file with no record of who
 * produced it, which is precisely what the sheet exists to prevent.
 */
public class XlsxReportWriterFactory implements ReportWriterFactory {

    private final ExportProperties.Xlsx settings;
    private final XlsxTemplateSource templates;
    private final ExportMessages messages;

    public XlsxReportWriterFactory(ExportProperties.Xlsx settings, XlsxTemplateSource templates,
                                   ExportMessages messages) {
        this.settings = settings;
        this.templates = templates;
        this.messages = messages;
    }

    @Override
    public ReportFormat format() {
        return StandardReportFormats.XLSX;
    }

    @Override
    public Set<String> supportedOptions() {
        return Set.of();
    }

    @Override
    public ReportWriter create(WriterContext context) throws IOException {
        Optional<InputStream> template = context.template() == null
                ? Optional.empty()
                : templates.open(context.template());
        return new XlsxReportWriter(context, settings.getFlushWindow(), settings.isFreezeHeader(),
                settings.isMetadataSheet(), template, messages);
    }
}
