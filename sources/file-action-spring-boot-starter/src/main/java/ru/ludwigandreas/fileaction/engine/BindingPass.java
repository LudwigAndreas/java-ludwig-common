package ru.ludwigandreas.fileaction.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import ru.ludwigandreas.fileaction.format.CoercionContext;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.RowReaderFactory;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.api.ColumnBinding;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Reads a submitted file, binds every row, and writes the ones that bound to a local artifact.
 *
 * <p>Nothing is applied here. Separating the read from the apply is what makes {@code VALIDATE_ONLY} the same
 * code as the first half of {@code CONFIRM}, and what lets a file be refused for a missing column before any
 * of its hundred thousand rows is touched.
 */
public class BindingPass {

    private final RowReaderFactoryRegistry readers;
    private final BoundRowStore boundRows;

    /**
     * Prepares the pass.
     *
     * @param readers   the reader factories, by format
     * @param boundRows how bound rows are written down
     */
    public BindingPass(RowReaderFactoryRegistry readers, BoundRowStore boundRows) {
        this.readers = readers;
        this.boundRows = boundRows;
    }

    /**
     * Reads and binds.
     *
     * @param action    the resolved action
     * @param content   a local file holding the submitted bytes
     * @param format    the sniffed format
     * @param coercion  the caller's locale and zone, and the workbook's date epoch
     * @param workDir   where the bound-row artifact is written
     * @param <R>       the row type
     * @return what the pass produced
     */
    public <R> BindOutcome bind(ResolvedAction<R> action, Path content, SourceFormat format,
                                CoercionContext coercion, Path workDir) {
        if (!action.formats().contains(format)) {
            throw new FileRejectedException(ProblemStatus.UNSUPPORTED_MEDIA_TYPE,
                    FileActionProblemCodes.UNSUPPORTED_FORMAT,
                    String.join(", ", action.acceptedFormatNames()));
        }
        RowReaderFactory factory = readers.require(format);
        Path artifact;
        try {
            artifact = Files.createTempFile(workDir, "ludwig-file-action-rows-", ".ndjson");
        } catch (IOException failed) {
            throw new UncheckedIOException("the bound-row artifact could not be created", failed);
        }
        RejectCollector rejects = new RejectCollector(action.rejectSample());
        Counters counters = new Counters();
        String sheet;
        try (RowReader reader = factory.open(content, action.binding(), action.budget());
                BoundRowStore.Writer writer = boundRows.writer(artifact)) {
            sheet = reader.sheet();
            requireDeclaredColumns(action, reader);
            reader.forEachRow(row -> {
                counters.read++;
                RowMaterialiser.Result<R> result = action.materialiser().materialise(row, coercion);
                if (!result.isBound()) {
                    rejects.rejectRow(result.problems());
                    // Carries on reading. Whether the rejects matter is the commit policy's decision and the
                    // threshold's, not this pass's - and stopping here would mean a user fixing one row at a
                    // time, which is the error report nobody wants.
                    return true;
                }
                try {
                    writer.write(new BoundRow<>(row.sheet(), row.displayedRow(), result.row()));
                } catch (IOException failed) {
                    throw new UncheckedIOException("a bound row could not be written down", failed);
                }
                counters.bound++;
                return true;
            });
            counters.bound = writer.count();
        } catch (IOException failed) {
            deleteQuietly(artifact);
            throw new UncheckedIOException("the submission could not be read", failed);
        } catch (RuntimeException propagated) {
            deleteQuietly(artifact);
            throw propagated;
        }
        if (counters.read == 0) {
            deleteQuietly(artifact);
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.EMPTY,
                    format.extension());
        }
        return new BindOutcome(artifact, counters.read, counters.bound, rejects, sheet);
    }

    /**
     * Refuses a file that has no header for a column the binding requires.
     *
     * <p>A file-level refusal, not a per-row one. Every row would reject for the same reason, so a reject report
     * with one entry per row says nothing a single message does not - and a user whose file is missing a column
     * needs to be told that, not handed four hundred identical rejects.
     */
    private static void requireDeclaredColumns(ResolvedAction<?> action, RowReader reader) {
        for (ColumnBinding column : action.binding().requiredColumns()) {
            boolean present = reader.headers().stream()
                    .anyMatch(header -> action.binding().columnForHeader(header)
                            .map(found -> found.field().equals(column.field()))
                            .orElse(false));
            if (!present) {
                throw new FileRejectedException(ProblemStatus.INVALID,
                        FileActionProblemCodes.MISSING_COLUMN, column.header(),
                        String.join(", ", reader.headers()));
            }
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The artifact is in a temp directory and the request is already failing; a delete failure here
            // would replace the real reason with a housekeeping one.
            return;
        }
    }

    /** Mutable counters, because a lambda cannot assign to a local. */
    private static final class Counters {
        private long read;
        private long bound;
    }
}
