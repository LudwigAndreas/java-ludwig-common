package ru.ludwigandreas.webcore.operation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How far along an operation is, for a client that wants to draw a bar.
 *
 * <h2>The total is nullable, and that is the whole point</h2>
 *
 * <p>A progress model that requires a denominator forces every producer to either lie or omit
 * progress entirely. {@code file-ingest} cannot know the record count of a streaming file before it
 * has read it, and inventing one would put a number in front of an operator that is wrong for the
 * whole run. With a null total the honest statement is still available: "four million records so
 * far", which is exactly what distinguishes a large file from a stuck run.
 *
 * <p>A null total therefore serializes as an absent member rather than as {@code 0} - see the
 * {@code @JsonInclude} on this record. {@code "total": 0} next to {@code "completed": 4000000} is
 * read by a client as "done, and then some", which is worse than saying nothing.
 *
 * @param completed how much has been done, in {@code unit}
 * @param total     how much there is in total, or null when the producer genuinely cannot know
 * @param unit      what is being counted - {@code rows}, {@code records}, {@code files}. Free text,
 *                  because the platform has no list of things worth counting and a fixed enum would
 *                  be one the next module has to extend
 * @param phase     a short, human-readable phase name - {@code reading}, {@code merging} - or null.
 *                  Free text and deliberately not a state: a phase nobody would act on differently
 *                  is a label, and promoting it to a status is how a sixth vocabulary starts
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationProgress(long completed, Long total, String unit, String phase) {

    private static final int PERCENT = 100;

    public OperationProgress {
        if (completed < 0) {
            throw new IllegalArgumentException("OperationProgress.completed must not be negative");
        }
        if (total != null && total < completed) {
            throw new IllegalArgumentException(
                    "OperationProgress.total must not be smaller than completed");
        }
    }

    /** Progress with no denominator: a count of what has been done so far. */
    public static OperationProgress of(long completed, String unit) {
        return new OperationProgress(completed, null, unit, null);
    }

    /** Progress with a denominator, for an operation that knows its size up front. */
    public static OperationProgress of(long completed, long total, String unit) {
        return new OperationProgress(completed, total, unit, null);
    }

    /** A copy of this progress carrying a phase label. */
    public OperationProgress inPhase(String newPhase) {
        return new OperationProgress(completed, total, unit, newPhase);
    }

    /**
     * Completion as a percentage, or null when there is no denominator to compute it against.
     *
     * <p>Derived rather than stored, so it cannot disagree with the two numbers it is derived from.
     * Null rather than zero for the same reason {@link #total()} is nullable.
     *
     * @return 0-100, or null
     */
    public Integer percent() {
        if (total == null || total == 0) {
            return null;
        }
        return (int) Math.min(PERCENT, completed * PERCENT / total);
    }
}
