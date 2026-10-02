package ru.ludwigandreas.fileaction.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.ludwigandreas.fileaction.format.RowProblem;

/**
 * Gathers the rejects of one submission, keeping a bounded sample and counting the rest.
 *
 * <h2>Why the sample is bounded here rather than at the database</h2>
 *
 * <p>A ten-thousand-reject upload would otherwise build ten thousand objects in the heap before anything
 * decided to keep only a hundred of them - so the bound has to be applied where the rejects are produced, not
 * where they are written. The count keeps rising after the sample stops growing, because the count is what the
 * threshold and the envelope are computed from.
 *
 * <p>The full set is not lost: it goes into the reject report artifact, written row by row as the file is read.
 * This class holds the part a paged endpoint serves.
 */
public class RejectCollector {

    private final int sampleLimit;
    private final List<RowProblem> sample = new ArrayList<>();

    /**
     * The rendered problem text per displayed row, for the annotated report.
     *
     * <p>Bounded by the same limit, for the same reason, and that is a real limitation: a submission with more
     * rejects than the sample allows gets an annotated workbook that only annotates the first {@code
     * sampleLimit} of them. Raising {@code reject-sample} is the lever, and a submission in that state has
     * almost certainly tripped its reject threshold and been refused whole anyway.
     */
    private final Map<Integer, List<String>> codesByRow = new LinkedHashMap<>();

    private long rejectedRows;
    private long skippedRows;

    /**
     * Collects into a sample of at most {@code sampleLimit} entries.
     *
     * @param sampleLimit how many rejects to keep
     */
    public RejectCollector(int sampleLimit) {
        this.sampleLimit = Math.max(sampleLimit, 0);
    }

    /**
     * Records every problem with one row, and counts the row once.
     *
     * <p>Once, not once per problem: a row with four bad cells is one rejected row, and counting it four times
     * would push a submission over a reject threshold expressed as a fraction of the rows read.
     *
     * @param problems the row's problems; empty is ignored
     */
    public void rejectRow(List<RowProblem> problems) {
        if (problems.isEmpty()) {
            return;
        }
        rejectedRows++;
        for (RowProblem problem : problems) {
            if (sample.size() < sampleLimit) {
                sample.add(problem);
            }
            if (codesByRow.size() < sampleLimit || codesByRow.containsKey(problem.address().row())) {
                codesByRow.computeIfAbsent(problem.address().row(), row -> new ArrayList<>())
                        .add(problem.code());
            }
        }
    }

    /** Counts a row the handler deliberately ignored. Does not count toward the reject threshold. */
    public void skipRow() {
        skippedRows++;
    }

    /** How many rows were refused. */
    public long rejectedRows() {
        return rejectedRows;
    }

    /** How many rows the handler deliberately ignored. */
    public long skippedRows() {
        return skippedRows;
    }

    /** The bounded sample, in the order the rows were read. */
    public List<RowProblem> sample() {
        return List.copyOf(sample);
    }

    /** The problem codes per displayed row, for rendering into the annotated report. */
    public Map<Integer, List<String>> codesByRow() {
        return Map.copyOf(codesByRow);
    }

    /** Whether anything was refused. */
    public boolean hasRejects() {
        return rejectedRows > 0;
    }
}
