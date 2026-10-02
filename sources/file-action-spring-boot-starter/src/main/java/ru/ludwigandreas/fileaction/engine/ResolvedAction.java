package ru.ludwigandreas.fileaction.engine;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import ru.ludwigandreas.fileaction.api.ActionMode;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.DocumentHandler;
import ru.ludwigandreas.fileaction.api.ErrorReportFormat;
import ru.ludwigandreas.fileaction.api.ExecutionMode;
import ru.ludwigandreas.fileaction.api.FileActionHandler;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.api.RowHandler;
import ru.ludwigandreas.fileaction.format.ReadBudget;
import ru.ludwigandreas.fileaction.format.SourceFormat;

/**
 * One action, with its configuration and its code resolved against each other - once, at startup.
 *
 * <h2>Why this exists rather than reading properties per request</h2>
 *
 * <p>Every field here is the result of a fallback chain: the action's own value, then the module default. Doing
 * that per upload would mean the same resolution running thousands of times, and - the reason that matters -
 * it would mean a misconfiguration being discovered by whichever user happened to upload first rather than by
 * the deployment that made it. Resolved here, {@code FileActionConfigurationValidator} can refuse the
 * application.
 *
 * @param name              the action name, which is both the configuration key and the path segment
 * @param handler           the bean, already narrowed to one of the two sealed shapes
 * @param binding           the handler's own binding
 * @param materialiser      the reflection over the row record, prepared once rather than per row
 * @param mode              how much of the lifecycle one submit performs
 * @param execution         whether the caller waits
 * @param commitPolicy      what happens to the other rows when one is refused. Never null here: an action that
 *                          left it unset did not get this far
 * @param formats           the formats this action accepts
 * @param maxBytes          the largest submission accepted
 * @param batchSize         how many rows one transaction covers
 * @param rejectSample      how many rejects are stored for the paged endpoint
 * @param rejectThreshold   the fraction of rejects above which the whole submission is refused
 * @param confirmTtl        how long a validated submission stays confirmable
 * @param errorReport       what the downloadable report is
 * @param requiredAuthority the authority a caller must hold, or null
 * @param budget            the ceilings a read of this action's files may not exceed
 * @param <R>               the row record type
 */
public record ResolvedAction<R>(String name, FileActionHandler<R> handler, RowBinding<R> binding,
                                RowMaterialiser<R> materialiser, ActionMode mode, ExecutionMode execution,
                                CommitPolicy commitPolicy, Set<SourceFormat> formats, long maxBytes,
                                int batchSize, int rejectSample, double rejectThreshold,
                                Duration confirmTtl, ErrorReportFormat errorReport,
                                String requiredAuthority, ReadBudget budget) {

    /** Normalises the format set and rejects an action missing something it cannot work without. */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor, assembled once by the
    // resolver. Every component is a separate configured fact and grouping any of them would only move the
    // count into a nested record nobody reads independently.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ResolvedAction {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A ResolvedAction needs its name");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Action '" + name + "' has no handler");
        }
        if (commitPolicy == null) {
            throw new IllegalArgumentException(
                    "Action '" + name + "' has no commit policy. There is no default, because every"
                            + " candidate default is silently wrong for some domain; see CommitPolicy");
        }
        formats = formats == null || formats.isEmpty()
                ? Set.of(SourceFormat.XLSX, SourceFormat.CSV) : Set.copyOf(formats);
    }

    /** Whether this action's handler applies rows one at a time. */
    public boolean isRowHandler() {
        return handler instanceof RowHandler<R>;
    }

    /** Whether this action's handler applies the whole document as one business fact. */
    public boolean isDocumentHandler() {
        return handler instanceof DocumentHandler<R>;
    }

    /**
     * The handler as a row handler.
     *
     * @return the handler
     * @throws IllegalStateException if this action's handler is a document handler
     */
    public RowHandler<R> asRowHandler() {
        if (handler instanceof RowHandler<R> rowHandler) {
            return rowHandler;
        }
        throw new IllegalStateException("Action '" + name + "' is not a row handler");
    }

    /**
     * The handler as a document handler.
     *
     * @return the handler
     * @throws IllegalStateException if this action's handler is a row handler
     */
    public DocumentHandler<R> asDocumentHandler() {
        if (handler instanceof DocumentHandler<R> documentHandler) {
            return documentHandler;
        }
        throw new IllegalStateException("Action '" + name + "' is not a document handler");
    }

    /**
     * Whether a submission with this many rejects out of this many rows is refused as a whole.
     *
     * @param rejected how many rows were refused
     * @param read     how many data rows were read
     * @return true when the threshold is exceeded
     */
    public boolean exceedsRejectThreshold(long rejected, long read) {
        if (read <= 0 || rejected <= 0) {
            return false;
        }
        return (double) rejected / (double) read > rejectThreshold;
    }

    /** The formats this action accepts, as a stable list for a message. */
    public List<String> acceptedFormatNames() {
        return formats.stream().map(SourceFormat::extension).sorted().toList();
    }
}
