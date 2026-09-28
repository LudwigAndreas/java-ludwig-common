package ru.ludwigandreas.example.catalog.service.report;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Set;
import ru.ludwigandreas.example.catalog.repository.entity.ProductStatus;
import ru.ludwigandreas.export.api.ReportParameters;

/**
 * What a requester chooses when asking for the catalogue extract.
 *
 * <h2>Parameters, filters and scope are three different things</h2>
 *
 * <p>Only the first of the three is here, and the distinction is worth stating because it is the one
 * an author of a second report will have to make.
 *
 * <p><b>Parameters</b> are the questions the report is <em>about</em>: the window of change dates, the
 * statuses of interest. They are declared as a typed object with constraints so that a missing or
 * malformed value is a 400 with per-field violations in the caller's language, produced at the edge,
 * before a run row exists.
 *
 * <p><b>Filters</b> are the requester's own narrowing, expressed as OData {@code $filter} and parsed
 * against {@code ProductEntity}'s {@code @Filterable} annotations. They are not here because nothing
 * in this service should have to enumerate them twice - the annotations already say what may be
 * filtered, and the report merely declares which of those columns it exposes.
 *
 * <p><b>Scope</b> is what the requester is entitled to see at all, and it is deliberately not
 * expressible here in any form. It is resolved from the {@code product} data-scope mapping against the
 * authorities re-resolved when the run executes, and conjoined into the query by the module. A
 * parameter that could widen it would be a parameter that could turn a scoped report into a full
 * table dump.
 *
 * @param changedFrom  the earliest last-modified instant to include; required, because the unbounded
 *                     version of this report is a full table scan somebody asked for by accident
 * @param changedUntil the exclusive upper bound; required for the same reason, and exclusive so that
 *                     consecutive daily extracts neither overlap nor gap
 * @param statuses     the statuses to include; empty means every status
 */
public record ProductReportParameters(
        @NotNull(message = "catalog.validation.report.changed-from.required") Instant changedFrom,
        @NotNull(message = "catalog.validation.report.changed-until.required") Instant changedUntil,
        Set<ProductStatus> statuses) implements ReportParameters {

    /** Normalizes the status set so the source never has to decide what null means. */
    public ProductReportParameters {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }

    /**
     * Rejects a reversed or empty window.
     *
     * <p>A constraint rather than a check in the canonical constructor, because the difference is what
     * the requester sees. A constructor that threw would surface as a binding failure with no field to
     * attach it to; this arrives through the same validation pass as the two {@code @NotNull}s and
     * becomes a localized violation the caller can act on.
     *
     * <p>Empty is rejected as well as reversed. A window whose ends are equal produces an empty file
     * rather than an error, and an empty file is the least diagnosable answer a report can give.
     *
     * @return whether the window runs forwards and covers something
     */
    @AssertTrue(message = "catalog.validation.report.window.ordered")
    public boolean isWindowOrdered() {
        return changedFrom == null || changedUntil == null || changedFrom.isBefore(changedUntil);
    }
}
