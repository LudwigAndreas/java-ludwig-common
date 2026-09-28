package ru.ludwigandreas.export.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.exception.ExportProblemCodes;
import ru.ludwigandreas.export.exception.UnknownReportFormatException;

/**
 * The registered formats, by id.
 *
 * <p>A class rather than a bare map because two things need it and neither should build its own: the
 * planner, which decides whether a request may ask for a format, and the executor, which turns the
 * format ids stored on a run row back into formats. Two independently built maps is how a format that
 * was removed from the estate stays resolvable in one of them.
 */
public class ReportWriterFactories {

    private final Map<String, ReportFormat> byId;

    public ReportWriterFactories(List<ReportWriterFactory> factories) {
        Map<String, ReportFormat> collected = new LinkedHashMap<>();
        factories.forEach(factory -> collected.put(factory.format().id(), factory.format()));
        this.byId = Map.copyOf(collected);
    }

    /**
     * The format with this id.
     *
     * @param formatId the id, from a request or from a stored run row
     * @return the format
     * @throws UnknownReportFormatException when nothing registers it - which for a stored run means
     *                                      the estate dropped a format a queued report was asked for,
     *                                      and the run should fail saying so rather than silently
     *                                      producing something else
     */
    public ReportFormat require(String formatId) {
        ReportFormat format = byId.get(formatId);
        if (format == null) {
            throw new UnknownReportFormatException(ExportProblemCodes.UNKNOWN_FORMAT, formatId, ids());
        }
        return format;
    }

    /** Every registered format id. */
    public Set<String> ids() {
        return byId.keySet();
    }
}
