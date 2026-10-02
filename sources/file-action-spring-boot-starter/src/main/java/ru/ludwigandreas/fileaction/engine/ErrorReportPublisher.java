package ru.ludwigandreas.fileaction.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.fileaction.api.ErrorReportFormat;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.format.CoercionContext;
import ru.ludwigandreas.fileaction.format.RowProblem;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.format.csv.RejectCsvWriter;
import ru.ludwigandreas.fileaction.format.xlsx.write.AnnotatedReportWriter;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.PutOptions;

/**
 * Produces a submission's downloadable reject report and puts it in the object store.
 *
 * <h2>Why the report is rendered here and not in the writers</h2>
 *
 * <p>A reject is stored as a message code and its arguments, never as a sentence - so that a report produced on
 * a worker thread, or read back months later, renders in the locale of the person who uploaded the file rather
 * than in whatever locale happened to be bound when the row was refused. This class is where that resolution
 * happens, which keeps the writers free of any notion of locale and keeps the module's one message bundle the
 * only place text comes from.
 *
 * <h2>Why a CSV submission gets a CSV report even when a workbook is configured</h2>
 *
 * <p>There is no workbook to annotate. Falling back is better than refusing: the user still gets a file they can
 * read, and the configured preference stays meaningful for the submissions it can apply to.
 */
public class ErrorReportPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorReportPublisher.class);

    private final ObjectStore objectStore;
    private final FileActionSettings settings;
    private final AnnotatedReportWriter workbooks;
    private final RejectCsvWriter csvs;
    private final ProblemRenderer messages;

    /**
     * Wires the publisher.
     *
     * @param objectStore where reports go
     * @param settings    the resolved prefixes
     * @param workbooks   the annotated-workbook writer
     * @param csvs        the CSV writer
     * @param messages    how a code and its arguments become a sentence in a given locale
     */
    public ErrorReportPublisher(ObjectStore objectStore, FileActionSettings settings,
                                AnnotatedReportWriter workbooks, RejectCsvWriter csvs,
                                ProblemRenderer messages) {
        this.objectStore = objectStore;
        this.settings = settings;
        this.workbooks = workbooks;
        this.csvs = csvs;
        this.messages = messages;
    }

    /** Turns a stored problem into a sentence in a locale. */
    @FunctionalInterface
    public interface ProblemRenderer {

        /**
         * Renders one problem.
         *
         * @param problem the stored code and arguments
         * @param context the locale and zone of the person who uploaded the file
         * @return the sentence
         */
        String render(RowProblem problem, CoercionContext context);
    }

    /**
     * Writes and uploads the report, if the action wants one and there is anything to report.
     *
     * @param action     the resolved action
     * @param submission the submission
     * @param content    the local copy of the submitted file, for an annotated workbook
     * @param format     what the submission was
     * @param bound      what the bind pass produced
     * @param coercion   the uploader's locale and zone
     * @return the report's object URI, or null when none was produced
     */
    public String publish(ResolvedAction<?> action, FileActionSubmissionEntity submission, Path content,
                          SourceFormat format, BindOutcome bound, CoercionContext coercion) {
        if (action.errorReport() == ErrorReportFormat.NONE || !bound.rejects().hasRejects()) {
            return null;
        }
        boolean annotate = action.errorReport() == ErrorReportFormat.ANNOTATED_WORKBOOK
                && format == SourceFormat.XLSX;
        try {
            return annotate
                    ? publishWorkbook(submission, content, bound, coercion)
                    : publishCsv(submission, bound, coercion);
        } catch (IOException failed) {
            // A report that could not be written must not fail the submission. The rows were applied or
            // refused already, the counts and the bounded sample are both stored, and turning a reporting
            // failure into a failed import would be strictly worse for the user than a missing download.
            LOG.warn("the reject report for submission {} could not be produced", submission.getId(),
                    failed);
            return null;
        }
    }

    private String publishWorkbook(FileActionSubmissionEntity submission, Path content, BindOutcome bound,
                                   CoercionContext coercion) throws IOException {
        Map<Integer, String> byRow = renderPerRow(bound, coercion);
        Path report = Files.createTempFile("ludwig-file-action-report-", ".xlsx");
        try {
            try (OutputStream out = Files.newOutputStream(report)) {
                workbooks.write(content, bound.sheet(), byRow, messages.render(
                        RowProblem.of(ru.ludwigandreas.fileaction.api.RowAddress.ofRow(bound.sheet(), 1),
                                "file-action.report.problems-column"), coercion), out);
            }
            String uri = ObjectKeys.errorReport(settings.artifactsPrefix(),
                    submission.getId(), SourceFormat.XLSX.extension());
            objectStore.put(uri, report, PutOptions.ofContentType(SourceFormat.XLSX.mediaType()));
            return uri;
        } finally {
            deleteQuietly(report);
        }
    }

    private String publishCsv(FileActionSubmissionEntity submission, BindOutcome bound,
                              CoercionContext coercion) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (RowProblem problem : bound.rejects().sample()) {
            rows.add(List.of(
                    problem.address().sheet() == null ? "" : problem.address().sheet(),
                    Integer.toString(problem.address().row()),
                    problem.address().column() == null ? "" : problem.address().column(),
                    messages.render(problem, coercion)));
        }
        Path report = Files.createTempFile("ludwig-file-action-report-", ".csv");
        try {
            try (OutputStream out = Files.newOutputStream(report)) {
                csvs.write(headers(coercion), rows, out);
            }
            String uri = ObjectKeys.errorReport(settings.artifactsPrefix(),
                    submission.getId(), SourceFormat.CSV.extension());
            objectStore.put(uri, report, PutOptions.ofContentType(SourceFormat.CSV.mediaType()));
            return uri;
        } finally {
            deleteQuietly(report);
        }
    }

    private List<String> headers(CoercionContext coercion) {
        return List.of(
                header("file-action.report.sheet", coercion),
                header("file-action.report.row", coercion),
                header("file-action.report.column", coercion),
                header("file-action.report.problem", coercion));
    }

    private String header(String code, CoercionContext coercion) {
        return messages.render(RowProblem.of(
                ru.ludwigandreas.fileaction.api.RowAddress.ofRow(null, 1), code), coercion);
    }

    /**
     * One sentence per rejected row, joining the problems of a row that had several.
     *
     * <p>Joined rather than one column per problem: a row with four bad cells would otherwise need four columns,
     * and the number of columns would depend on the worst row in the file.
     */
    private Map<Integer, String> renderPerRow(BindOutcome bound, CoercionContext coercion) {
        Map<Integer, List<String>> byRow = new LinkedHashMap<>();
        for (RowProblem problem : bound.rejects().sample()) {
            byRow.computeIfAbsent(problem.address().row(), row -> new ArrayList<>())
                    .add(messages.render(problem, coercion));
        }
        Map<Integer, String> joined = new LinkedHashMap<>();
        byRow.forEach((row, problems) -> joined.put(row, String.join("; ", problems)));
        return joined;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException leftBehind) {
            LOG.warn("could not delete the temporary report {}", path, leftBehind);
        }
    }
}
