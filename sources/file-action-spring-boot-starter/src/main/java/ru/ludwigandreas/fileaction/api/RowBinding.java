package ru.ludwigandreas.fileaction.api;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * How one sheet's columns become one typed record: declared in code, as a constant.
 *
 * <h2>Why this is code and not a row in a table</h2>
 *
 * <p>The same decision {@code export-spring-boot-starter} takes for its report definitions and
 * {@code user-settings-spring-boot-starter} takes for its setting definitions, for the same reason. The
 * obvious way to make an import engine reusable is to let an administrator declare the mapping as data
 * through a UI. That is precisely how an import module becomes a second, untested binding layer with no
 * type checking, no refactoring support and no way to answer "which imports write this field".
 *
 * <p>Declared as a constant, the field names are checked against the row record's components at
 * startup, the target types come from the record, and renaming a component is a startup failure in
 * every environment rather than an empty column in production.
 *
 * <p>What stays data is the part that genuinely varies per deployment - the size budget, the commit
 * policy, the confirm TTL - and that lives in YAML.
 *
 * <pre>{@code
 * public static final RowBinding<OrderLine> ORDER_LINES = RowBinding.of(OrderLine.class)
 *         .sheet("Orders")
 *         .column("sku", "SKU").aliases("Article")
 *         .column("quantity", "Qty")
 *         .column("comment", "Comment").optional()
 *         .build();
 * }</pre>
 *
 * @param <R> the row record this binding fills
 */
public final class RowBinding<R> {

    private final Class<R> rowType;
    private final String sheet;
    private final List<ColumnBinding> columns;
    private final Map<String, ColumnBinding> byNormalisedHeader;

    private RowBinding(Class<R> rowType, String sheet, List<ColumnBinding> columns) {
        this.rowType = rowType;
        this.sheet = sheet;
        this.columns = List.copyOf(columns);
        this.byNormalisedHeader = index(this.columns);
    }

    /**
     * Starts a binding for a row record type.
     *
     * @param rowType the record the binding fills; must be a record, because the binding resolves the
     *                target types and the construction order from its components rather than from a
     *                second declaration that could disagree with them
     * @param <R>     the row type
     * @return a builder
     */
    public static <R> Builder<R> of(Class<R> rowType) {
        return new Builder<>(rowType);
    }

    /** The record type a bound row is. */
    public Class<R> rowType() {
        return rowType;
    }

    /**
     * The sheet this binding reads.
     *
     * @return the sheet name, or {@code null} to read the first sheet - which is also what a CSV, which
     *         has no sheets, always does
     */
    public String sheet() {
        return sheet;
    }

    /** The columns, in declaration order, which is also the order the generated template writes them. */
    public List<ColumnBinding> columns() {
        return columns;
    }

    /** The columns a file must carry a header for. */
    public List<ColumnBinding> requiredColumns() {
        return columns.stream().filter(ColumnBinding::required).toList();
    }

    /**
     * Finds the column a header text binds to.
     *
     * @param header the header text as it appears in the submitted file
     * @return the column, or empty when the file carries a header this binding does not declare - which
     *         is not an error: a user's sheet routinely has columns the action does not care about
     */
    public Optional<ColumnBinding> columnForHeader(String header) {
        return Optional.ofNullable(byNormalisedHeader.get(ColumnBinding.normalise(header)));
    }

    private static Map<String, ColumnBinding> index(List<ColumnBinding> columns) {
        Map<String, ColumnBinding> index = new LinkedHashMap<>();
        for (ColumnBinding column : columns) {
            for (String header : column.matchableHeaders()) {
                ColumnBinding clash = index.put(header, column);
                if (clash != null && clash != column) {
                    throw new IllegalArgumentException(
                            "Two columns of this binding both match the header '" + header + "': '"
                                    + clash.field() + "' and '" + column.field()
                                    + "'. A file carrying that header could bind to either, so the"
                                    + " binding is ambiguous rather than merely untidy");
                }
            }
        }
        return Map.copyOf(index);
    }

    /**
     * Builds a {@link RowBinding}, checking each declared field against the row record's components.
     *
     * <p>The check happens here, when the constant is initialised, rather than at first use. A binding
     * naming a field the record does not have is a class-initialisation failure at startup, which is
     * every environment including the developer's; at first use it would be a failure for whichever
     * user happened to upload first.
     *
     * @param <R> the row type
     */
    public static final class Builder<R> {

        private final Class<R> rowType;
        private final List<ColumnBinding> columns = new ArrayList<>();
        private String sheet;

        private Builder(Class<R> rowType) {
            if (rowType == null) {
                throw new IllegalArgumentException("A RowBinding needs a row type");
            }
            if (!rowType.isRecord()) {
                throw new IllegalArgumentException(
                        "A RowBinding's row type must be a record, was " + rowType.getName()
                                + ". The binding reads the target types and the construction order from"
                                + " the record's components, so a class would need them declared a"
                                + " second time and the two could disagree");
            }
            this.rowType = rowType;
        }

        /**
         * Names the sheet to read.
         *
         * @param name the sheet name; omit to read the first sheet
         * @return this builder
         */
        public Builder<R> sheet(String name) {
            this.sheet = name;
            return this;
        }

        /**
         * Declares a required column.
         *
         * @param field  the record component it fills
         * @param header the canonical header text
         * @return this builder, on which {@code aliases}, {@code required} and {@code optional} apply to
         *         this column
         */
        public Builder<R> column(String field, String header) {
            columns.add(new ColumnBinding(field, header, Set.of(), true));
            return this;
        }

        /**
         * Adds header texts that bind to the most recently declared column.
         *
         * @param aliases the alternative header texts
         * @return this builder
         */
        public Builder<R> aliases(String... aliases) {
            ColumnBinding last = last("aliases");
            Set<String> merged = new LinkedHashSet<>(last.aliases());
            merged.addAll(Arrays.asList(aliases));
            columns.set(columns.size() - 1,
                    new ColumnBinding(last.field(), last.header(), merged, last.required()));
            return this;
        }

        /**
         * Marks the most recently declared column required. Columns are required by default; this
         * exists so a binding can say so where it matters rather than relying on the reader knowing
         * the default.
         *
         * @return this builder
         */
        public Builder<R> required() {
            return requiredness(true);
        }

        /**
         * Marks the most recently declared column optional.
         *
         * @return this builder
         */
        public Builder<R> optional() {
            return requiredness(false);
        }

        private Builder<R> requiredness(boolean required) {
            ColumnBinding last = last(required ? "required" : "optional");
            columns.set(columns.size() - 1,
                    new ColumnBinding(last.field(), last.header(), last.aliases(), required));
            return this;
        }

        private ColumnBinding last(String called) {
            if (columns.isEmpty()) {
                throw new IllegalStateException(
                        called + "() applies to the column declared before it, and no column has been"
                                + " declared yet");
            }
            return columns.get(columns.size() - 1);
        }

        /**
         * Validates and builds the binding.
         *
         * @return the binding
         */
        public RowBinding<R> build() {
            if (columns.isEmpty()) {
                throw new IllegalArgumentException(
                        "A RowBinding for " + rowType.getSimpleName() + " declares no columns");
            }
            Set<String> components = new LinkedHashSet<>();
            for (RecordComponent component : rowType.getRecordComponents()) {
                components.add(component.getName());
            }
            Set<String> declared = new LinkedHashSet<>();
            for (ColumnBinding column : columns) {
                if (!components.contains(column.field())) {
                    throw new IllegalArgumentException(
                            "Column '" + column.header() + "' binds to field '" + column.field()
                                    + "', which " + rowType.getName() + " does not have. Its components"
                                    + " are " + components);
                }
                if (!declared.add(column.field())) {
                    throw new IllegalArgumentException(
                            "Field '" + column.field() + "' is bound by two columns of this binding");
                }
            }
            return new RowBinding<>(rowType, sheet, columns);
        }
    }
}
