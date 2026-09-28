package ru.ludwigandreas.export.api;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * One column of a report: where its value comes from, what it is, and who may see it.
 *
 * <pre>{@code
 * Column<OrderRow, BigDecimal> TOTAL = Column
 *         .<OrderRow, BigDecimal>of("total", BigDecimal.class)
 *         .extractor(OrderRow::total)
 *         .headerKey("report.orders.column.total")
 *         .format(CellFormat.money(Currency.getInstance("EUR")))
 *         .aggregate(Aggregate.SUM)
 *         .width(14)
 *         .build();
 * }</pre>
 *
 * <h2>Why the header is a key and the extractor is a function</h2>
 *
 * <p>The header is a message key, never a string, because the same definition serves an English and
 * a Russian reader and the file each of them downloads should be in their language down to the
 * column captions. Checkstyle's non-ASCII rule makes the alternative a build failure rather than a
 * convention, and the startup validator refuses a key missing from either bundle, so a column
 * cannot ship with a header that renders as its own key.
 *
 * <p>The extractor is a {@code Function} rather than a property name because a property name is a
 * string the compiler does not check: renaming a field on the row type would leave the definition
 * compiling and the column empty. As a method reference it is a compile error instead.
 *
 * <h2>Visibility is absence, not blanking</h2>
 *
 * <p>A column the requester may not see is removed from the file - from the header row as well as
 * from every data row - rather than written empty. A blank column tells the reader that the data
 * exists and they were not given it, which is itself disclosure, and it leaves a column of empties
 * that every downstream spreadsheet has to be taught to ignore.
 *
 * @param <R> the row type the extractor reads
 * @param <V> the column's value type
 */
@Getter
@EqualsAndHashCode(of = "id")
public final class Column<R, V> {

    /**
     * Column ids are lowercase, dash- or dot-separated. Enforced because an id is a wire identifier:
     * it is what a request names in its column subset, what a saved configuration stores, and what
     * the startup validator matches a sort key against.
     */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9]*([.\\-][a-z0-9]+)*$");

    private static final int MAX_ID_LENGTH = 64;

    /** Default column width in characters, used when a definition does not declare one. */
    public static final int DEFAULT_WIDTH = 16;

    /** Stable identifier, unique within a definition. */
    private final String id;

    /** The type the extractor yields, and the type the renderer is resolved for. */
    private final Class<V> valueType;

    /** Reads the value out of a row. May return null; see {@link #nullPolicy}. */
    private final Function<R, V> extractor;

    /** Message key for the header cell, resolved against the run's locale. */
    private final String headerKey;

    /** How the value is presented. */
    private final CellFormat format;

    /**
     * Declared width in characters.
     *
     * <p>Declared rather than measured because a streaming sheet cannot be auto-sized: POI's
     * {@code autoSizeColumn} needs every cell of the column in memory, which is precisely what the
     * streaming writer exists to avoid. A width here is the difference between a usable file and one
     * whose every date column shows as {@code ####}.
     */
    private final int width;

    /** The totals-row function this column contributes; {@link Aggregate#NONE} by default. */
    private final Aggregate aggregate;

    /**
     * The enrichment stage that supplies this column's value, or null for a column read from the
     * base row.
     *
     * <p>Naming it is what lets the registry reject at startup a column fed by a stage the definition
     * does not declare, and what lets the engine mark exactly the right cells when that stage
     * degrades. Without it, a degraded stage would either mark every cell or none.
     */
    private final String requiredStage;

    /**
     * Authorities that may see this column. Empty means every requester who may run the report.
     *
     * <p>Any one of them suffices; this is a disjunction, not a conjunction. Requiring all of them
     * would make the common case - "managers or auditors" - inexpressible without a synthetic
     * authority, and a column's visibility is a question about roles a person might hold rather than
     * about a role they must hold all of.
     */
    private final Set<String> visibleFor;

    /**
     * Whether this column's values are personal data.
     *
     * <p>A flagged column is redacted in the audit trail, in logs and in any problem document, and
     * never appears in a metric tag. It is still written to the file - that is what the report is
     * for - but the trail of who exported it says which columns rather than which values.
     */
    private final boolean pii;

    /** What to do when the extractor yields null. */
    private final NullPolicy nullPolicy;

    /** Explicit renderer, overriding the one the engine would derive from {@link #valueType}. */
    private final CellRenderer<V> renderer;

    // SUPPRESS CHECKSTYLE ParameterNumber - the canonical constructor behind @Builder. The rule
    // exists to catch call sites nobody can read; nothing calls this positionally, because the
    // builder is the only way in.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Builder
    private Column(String id, Class<V> valueType, Function<R, V> extractor, String headerKey,
                   CellFormat format, Integer width, Aggregate aggregate, String requiredStage,
                   Set<String> visibleFor, boolean pii, NullPolicy nullPolicy, CellRenderer<V> renderer) {
        this.id = requireId(id);
        this.valueType = require(valueType, this.id, "a value type");
        this.extractor = require(extractor, this.id, "an extractor");
        this.headerKey = requireText(headerKey, this.id, "a header message key");
        this.format = format == null ? CellFormat.text() : format;
        this.width = requireWidth(width, this.id);
        this.aggregate = aggregate == null ? Aggregate.NONE : aggregate;
        this.requiredStage = requiredStage;
        this.visibleFor = visibleFor == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(visibleFor));
        this.pii = pii;
        this.nullPolicy = nullPolicy == null ? NullPolicy.EMPTY : nullPolicy;
        this.renderer = renderer;
        if (this.nullPolicy == NullPolicy.ZERO && !this.format.isNumeric()) {
            throw new IllegalArgumentException(
                    "Column " + this.id + " declares NullPolicy.ZERO on a non-numeric format: "
                            + this.format.kind());
        }
    }

    private static String requireId(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("A column needs an id");
        }
        if (candidate.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "Column id is longer than " + MAX_ID_LENGTH + " characters: " + candidate);
        }
        if (!ID_PATTERN.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    "Column id must be lowercase dot- or dash-separated segments, was: " + candidate);
        }
        return candidate;
    }

    private static <T> T require(T value, String columnId, String what) {
        if (value == null) {
            throw new IllegalArgumentException("Column " + columnId + " needs " + what);
        }
        return value;
    }

    private static String requireText(String value, String columnId, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Column " + columnId + " needs " + what);
        }
        return value;
    }

    private static int requireWidth(Integer width, String columnId) {
        if (width == null) {
            return DEFAULT_WIDTH;
        }
        if (width < 1) {
            throw new IllegalArgumentException("Column " + columnId + " has a width below 1: " + width);
        }
        return width;
    }

    /**
     * Entry point for the builder, so the two things a column cannot omit are named up front.
     *
     * @param id        the column's identifier within its definition
     * @param valueType the type the extractor yields
     * @param <R>       the row type
     * @param <V>       the value type
     * @return a builder still needing an extractor and a header key
     */
    public static <R, V> ColumnBuilder<R, V> of(String id, Class<V> valueType) {
        return Column.<R, V>builder().id(id).valueType(valueType);
    }

    /** Whether a requester holding these authorities may see this column. */
    public boolean isVisibleTo(Set<String> authorities) {
        if (visibleFor.isEmpty()) {
            return true;
        }
        if (authorities == null || authorities.isEmpty()) {
            return false;
        }
        return visibleFor.stream().anyMatch(authorities::contains);
    }

    /** Whether this column's value comes from an enrichment stage rather than from the base row. */
    public boolean isEnriched() {
        return requiredStage != null;
    }

    /** Never includes a sample value; this string ends up in startup logs and exception messages. */
    @Override
    public String toString() {
        return "Column(" + id + ": " + valueType.getSimpleName() + "/" + format.kind() + ")";
    }
}
