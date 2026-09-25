package ru.ludwigandreas.export.format.csv;

import java.io.IOException;
import java.util.Set;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportWriter;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.api.StandardReportFormats;
import ru.ludwigandreas.export.api.WriterContext;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.i18n.ExportMessages;

/**
 * Creates CSV writers, and is the worked example of what a format seam looks like.
 *
 * <p>A service adding PDF writes one of these and registers it as a bean. Nothing else in the module
 * changes: the engine dispatches on {@link #format()}, the registry learns what the format can carry
 * from {@link ReportFormat#capabilities()}, and the request layer learns which options it accepts
 * from {@link #supportedOptions()}.
 *
 * <p>The configured defaults are read here rather than inside the writer so that the writer takes a
 * fully resolved {@link CsvProfile} and has no opinion about where it came from - which is what lets
 * a test drive the writer with an exact profile instead of a property source.
 */
public class CsvReportWriterFactory implements ReportWriterFactory {

    private final ExportProperties.Csv settings;
    private final ExportMessages messages;

    public CsvReportWriterFactory(ExportProperties.Csv settings, ExportMessages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    @Override
    public ReportFormat format() {
        return StandardReportFormats.CSV;
    }

    @Override
    public Set<String> supportedOptions() {
        return CsvProfile.OPTIONS;
    }

    @Override
    public ReportWriter create(WriterContext context) throws IOException {
        CsvProfile profile = CsvProfile.resolve(context.options(), settings.getProfile(),
                settings.isAllowRequestOverride(), context.render().locale());
        return new CsvReportWriter(context, profile, messages);
    }
}
