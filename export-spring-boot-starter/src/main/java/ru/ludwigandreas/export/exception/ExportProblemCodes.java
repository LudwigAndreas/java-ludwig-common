package ru.ludwigandreas.export.exception;

/**
 * The problem codes this module emits, and therefore the ones a caller may branch on.
 *
 * <p>Constants rather than literals at the throw sites because a code is published in the response
 * body: a client is expected to branch on {@code code} instead of parsing a translated
 * {@code detail}, which makes each one part of the API contract. The keys in
 * {@code i18n/ludwig-export-messages.properties} are exactly these values, plus a {@code .title}
 * sibling for each, in both locales; the startup validator and Checkstyle's {@code Translation}
 * check between them make a missing key a build failure rather than a message that renders as its
 * own key.
 */
public final class ExportProblemCodes {

    /** Namespace prefix for every code below, so a caller can spot an export error at a glance. */
    public static final String PREFIX = "ludwig.export.error.";

    /** No definition is registered under the requested key. */
    public static final String UNKNOWN_DEFINITION = PREFIX + "unknown-definition";
    /** The requested format id matches no registered writer factory. */
    public static final String UNKNOWN_FORMAT = PREFIX + "unknown-format";
    /** The format exists but the definition does not allow it; carries the allowed set. */
    public static final String FORMAT_NOT_ALLOWED = PREFIX + "format-not-allowed";
    /** The format is disabled estate-wide by {@code ludwig.export.formats.enabled}. */
    public static final String FORMAT_DISABLED = PREFIX + "format-disabled";
    /** The definition has several sheets and the chosen format has one; carries the strategy. */
    public static final String MULTI_SHEET_UNSUPPORTED = PREFIX + "multi-sheet-unsupported";
    /** A per-format option was supplied that the chosen writer does not accept. */
    public static final String UNKNOWN_FORMAT_OPTION = PREFIX + "unknown-format-option";
    /** The request's parameters could not be bound, or failed the definition's constraints. */
    public static final String PARAMETERS_INVALID = PREFIX + "parameters-invalid";
    /** One parameter could not be converted to the type the definition declared. */
    public static final String PARAMETER_INVALID = PREFIX + "parameter-invalid";
    /** A per-format option's value is not valid for that option. */
    public static final String INVALID_FORMAT_OPTION = PREFIX + "invalid-format-option";
    /** The request carried a filter for a report that declares no filterable columns. */
    public static final String NOT_FILTERABLE = PREFIX + "not-filterable";
    /** The column subset names a column the definition does not declare. */
    public static final String UNKNOWN_COLUMN = PREFIX + "unknown-column";
    /** The column subset names a column the requester's authorities do not cover. */
    public static final String COLUMN_FORBIDDEN = PREFIX + "column-forbidden";
    /** The requested sort names a column the source cannot order by; carries the sortable set. */
    public static final String UNSORTABLE_COLUMN = PREFIX + "unsortable-column";
    /** The requester may not run this definition at all. */
    public static final String REPORT_FORBIDDEN = PREFIX + "report-forbidden";
    /** The run produced more rows than the definition's cap allows; carries limit and observed. */
    public static final String ROW_LIMIT_EXCEEDED = PREFIX + "row-limit-exceeded";
    /** The run exceeded its wall-clock budget; carries limit and observed. */
    public static final String TIME_BUDGET_EXCEEDED = PREFIX + "time-budget-exceeded";
    /** The requester already has as many runs in flight, or today, as their quota allows. */
    public static final String QUOTA_EXCEEDED = PREFIX + "quota-exceeded";
    /** The requester lost the access the run was accepted under before it executed. */
    public static final String ACCESS_REVOKED = PREFIX + "access-revoked";

    /**
     * A stage needs the requester's own token and the run is not on the requester's thread.
     *
     * <p>Deliberately its own code rather than a generic failure, and deliberately not a silent
     * fallback to the service account: the two produce different files, and a caller who asked for a
     * report scoped by a partner has to be told that it could not be scoped that way rather than handed
     * the other one.
     */
    public static final String IDENTITY_UNAVAILABLE = PREFIX + "identity-unavailable";
    /** The run exists but is not in a state that has an output to download. */
    public static final String OUTPUT_NOT_READY = PREFIX + "output-not-ready";
    /** The output existed and has been removed by the retention purge. */
    public static final String OUTPUT_EXPIRED = PREFIX + "output-expired";
    /** A saved configuration no longer matches the definition it is bound to. */
    public static final String SAVED_REPORT_STALE = PREFIX + "saved-report-stale";
    /** An enrichment stage failed and its policy is to fail the report; carries the stage name. */
    public static final String ENRICHMENT_FAILED = PREFIX + "enrichment-failed";
    /** A key an enrichment stage requires was not known to the partner under FAIL_REPORT. */
    public static final String ENRICHMENT_KEY_MISSING = PREFIX + "enrichment-key-missing";
    /** The file could not be written: disk full, permissions, a writer error. */
    public static final String WRITE_FAILED = PREFIX + "write-failed";
    /** The sink refused the finished file after the store retries were exhausted. */
    public static final String SINK_UNAVAILABLE = PREFIX + "sink-unavailable";
    /** A row's value was absent in a column whose null policy is to fail. */
    public static final String REQUIRED_VALUE_MISSING = PREFIX + "required-value-missing";

    private ExportProblemCodes() {
    }
}
