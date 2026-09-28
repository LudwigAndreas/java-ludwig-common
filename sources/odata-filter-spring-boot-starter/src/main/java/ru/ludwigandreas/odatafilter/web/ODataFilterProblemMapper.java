package ru.ludwigandreas.odatafilter.web;

import ru.ludwigandreas.odatafilter.exception.FilterAccessDeniedException;
import ru.ludwigandreas.odatafilter.exception.FilterDepthExceededException;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;
import ru.ludwigandreas.odatafilter.exception.FilterValidationException;
import ru.ludwigandreas.odatafilter.exception.ODataFilterException;
import ru.ludwigandreas.odatafilter.exception.PageSizeExceededException;
import ru.ludwigandreas.odatafilter.exception.UnfilterableFieldException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * States what this library's failures mean, and lets the application's problem pipeline say it.
 *
 * <p>This replaces {@link ODataFilterExceptionHandler}, the advice this module used to ship, and it
 * is worth recording why that advice was a mistake. Its messages were the exceptions' own
 * developer-facing English - "Property 'supplierCost' cannot be used in $filter/$orderby: not
 * annotated @Filterable" - and a library cannot translate those, because the bundle would have to
 * live in the application. So a service that answered every other error in the caller's language had
 * one subsystem answering in English, and the only way out was
 * {@code odata.filter.web.problem-detail-advice-enabled=false} plus a hand-written advice that
 * re-handled all seven exception types. Every service using this module wrote that same advice.
 *
 * <p>A mapper plus a message bundle removes both halves of the problem: the meaning is declared here
 * once, the text ships in {@code i18n/ludwig-odata-filter-messages} in every locale this module
 * supports, and a service that wants different wording defines the same key in its own bundle.
 *
 * <p>Note what does <em>not</em> change: the deliberate conflation of "no such field" with "field
 * exists but is not filterable" into one 400, and the separate 403 for a field the caller lacks a
 * role for. Those are security decisions that belong to this module, and stating them in a mapper
 * keeps them here rather than re-deciding them in each application's advice.
 */
public class ODataFilterProblemMapper implements ExceptionProblemMapper {

    /** Namespace for this module's codes, mirroring the {@code i18n} bundle's keys. */
    public static final String CODE_PREFIX = "ludwig.odata.error.";

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof ODataFilterException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        if (exception instanceof FilterAccessDeniedException e) {
            // 403, and separate from the 400 below so an operator can alert on callers probing
            // fields they have no role for without that signal being buried in ordinary typos.
            return ProblemDefinition.of(ProblemStatus.FORBIDDEN, CODE_PREFIX + "field-forbidden")
                    .withProperty("property", e.propertyPath());
        }
        if (exception instanceof UnfilterableFieldException e) {
            // Intentionally the same answer for "no such field" and "that field is not filterable":
            // distinguishing them would turn $filter into a way to enumerate the entity's columns.
            return ProblemDefinition.of(ProblemStatus.INVALID, CODE_PREFIX + "unfilterable-field")
                    .withProperty("property", e.propertyPath());
        }
        if (exception instanceof FilterDepthExceededException e) {
            return ProblemDefinition.of(
                            ProblemStatus.INVALID, CODE_PREFIX + "depth-exceeded", e.maxDepth())
                    .withProperty("maxDepth", e.maxDepth())
                    .withProperty("actualDepth", e.actualDepth());
        }
        if (exception instanceof PageSizeExceededException e) {
            return ProblemDefinition.of(ProblemStatus.INVALID, CODE_PREFIX + "page-size-exceeded", e.max())
                    .withProperty("maxPageSize", e.max())
                    .withProperty("requestedPageSize", e.requested());
        }
        if (exception instanceof FilterSyntaxException) {
            // The parser's message quotes an offset into the caller's own expression. It is not
            // translated and not forwarded; the caller has the expression and can see the offset.
            return ProblemDefinition.of(ProblemStatus.INVALID, CODE_PREFIX + "syntax");
        }
        if (exception instanceof FilterValidationException) {
            return ProblemDefinition.of(ProblemStatus.INVALID, CODE_PREFIX + "validation");
        }
        // A subtype added later still gets a localized 400 rather than a 500, because the base type
        // has a key of its own. Adding a branch here only ever makes an answer more specific.
        return ProblemDefinition.of(ProblemStatus.INVALID, CODE_PREFIX + "invalid-query");
    }

    @Override
    public int getOrder() {
        return DEFAULT_MODULE_ORDER;
    }
}
