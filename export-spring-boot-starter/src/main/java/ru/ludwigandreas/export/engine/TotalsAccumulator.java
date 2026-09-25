package ru.ludwigandreas.export.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import ru.ludwigandreas.export.api.Aggregate;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.ColumnSpec;

/**
 * The totals row, computed as the rows go past.
 *
 * <h2>One pass, constant memory</h2>
 *
 * <p>There is no second pass over the output and no buffering of a column. That constraint is what
 * decided which functions {@link Aggregate} has: every one of them is a fold over a stream with a
 * fixed amount of state, and a median or a distinct count - which would need the column held - is
 * deliberately absent. A report that needs one computes it in the query that feeds the
 * {@code RowSource}, where the database can do it without holding anything in this JVM.
 *
 * <h2>A sum across currencies is refused, not converted</h2>
 *
 * <p>Summing a money column whose rows carry different currencies has no correct answer without an
 * exchange rate and a date, neither of which this module has any business inventing. The totals cell
 * becomes a localized marker instead. Producing a number there - by ignoring the currency, or by
 * taking the first one - would put a figure in a financial document that is wrong in a way nobody
 * reading it could detect.
 */
final class TotalsAccumulator {

    /** Precision for the running mean; a division is the only operation here that is not exact. */
    private static final MathContext AVERAGE_CONTEXT = new MathContext(34, RoundingMode.HALF_UP);

    /** Marker written where an aggregate has no defined answer for the values it saw. */
    static final String UNDEFINED_TOTAL_KEY = "ludwig.export.cell.unavailable";

    private final List<Slot> slots;

    /**
     * @param columns the sheet's visible columns, whose declared aggregates decide what is folded
     * @param zone    the run's timezone, so a MIN or MAX over a datetime column is presented in the
     *                same zone as the cells it was taken from rather than in the server's
     */
    TotalsAccumulator(List<ColumnSpec> columns, ZoneId zone) {
        List<Slot> built = new ArrayList<>(columns.size());
        for (ColumnSpec column : columns) {
            built.add(new Slot(column.aggregate(), zone));
        }
        this.slots = List.copyOf(built);
    }

    /** Whether any column of this sheet contributes a total at all. */
    boolean hasAnyAggregate() {
        return slots.stream().anyMatch(slot -> slot.aggregate != Aggregate.NONE);
    }

    /** Folds one rendered row into the running totals. */
    void accept(List<CellValue> cells) {
        for (int i = 0; i < slots.size(); i++) {
            slots.get(i).accept(cells.get(i));
        }
    }

    /** The totals row, in column order; an empty list when no column asked for one. */
    List<CellValue> totals() {
        if (!hasAnyAggregate()) {
            return List.of();
        }
        List<CellValue> cells = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            cells.add(slot.total());
        }
        return List.copyOf(cells);
    }

    /** One column's running state. */
    private static final class Slot {

        private final Aggregate aggregate;
        private final ZoneId zone;

        private long count;
        private BigDecimal numeric;
        private Currency currency;
        private boolean currencyConflict;
        private LocalDate minDate;
        private LocalDate maxDate;
        private Instant minInstant;
        private Instant maxInstant;

        Slot(Aggregate aggregate, ZoneId zone) {
            this.aggregate = aggregate;
            this.zone = zone;
        }

        void accept(CellValue value) {
            if (aggregate == Aggregate.NONE || value instanceof CellValue.Empty) {
                return;
            }
            if (value instanceof CellValue.Error) {
                // A degraded cell has no value to fold in. It still does not count towards an
                // average's divisor, because dividing by rows that produced nothing would quietly
                // depress the mean by however much the partner outage happened to cover.
                return;
            }
            count++;
            if (value instanceof CellValue.Number number) {
                foldNumber(number.value());
            } else if (value instanceof CellValue.Money money) {
                foldCurrency(money.currency());
                foldNumber(money.amount());
            } else if (value instanceof CellValue.Date date) {
                foldDate(date.value());
            } else if (value instanceof CellValue.DateTime dateTime) {
                foldInstant(dateTime.value());
            }
        }

        private void foldDate(LocalDate seen) {
            minDate = minDate == null || seen.isBefore(minDate) ? seen : minDate;
            maxDate = maxDate == null || seen.isAfter(maxDate) ? seen : maxDate;
        }

        private void foldInstant(Instant seen) {
            minInstant = minInstant == null || seen.isBefore(minInstant) ? seen : minInstant;
            maxInstant = maxInstant == null || seen.isAfter(maxInstant) ? seen : maxInstant;
        }

        CellValue total() {
            if (aggregate == Aggregate.NONE) {
                return CellValue.Empty.INSTANCE;
            }
            if (aggregate == Aggregate.COUNT) {
                return new CellValue.Number(BigDecimal.valueOf(count));
            }
            if (count == 0) {
                // No values at all is not an error and not a zero: a column of entirely empty cells
                // has no sum, and writing 0 would be a claim about data that is not there.
                return CellValue.Empty.INSTANCE;
            }
            if (currencyConflict) {
                return new CellValue.Error(UNDEFINED_TOTAL_KEY);
            }
            return numericOrTemporalTotal();
        }

        private CellValue numericOrTemporalTotal() {
            if (numeric != null) {
                BigDecimal result = aggregate == Aggregate.AVERAGE
                        ? numeric.divide(BigDecimal.valueOf(count), AVERAGE_CONTEXT)
                        : numeric;
                return currency == null
                        ? new CellValue.Number(result)
                        : new CellValue.Money(result, currency);
            }
            if (minDate != null) {
                return temporalDate();
            }
            if (minInstant != null) {
                return temporalInstant();
            }
            return new CellValue.Error(UNDEFINED_TOTAL_KEY);
        }

        private CellValue temporalDate() {
            if (aggregate == Aggregate.MIN) {
                return new CellValue.Date(minDate);
            }
            if (aggregate == Aggregate.MAX) {
                return new CellValue.Date(maxDate);
            }
            // Summing or averaging dates is not defined, and the definition asked for it: say so in
            // the cell rather than failing a finished report over a totals row.
            return new CellValue.Error(UNDEFINED_TOTAL_KEY);
        }

        private CellValue temporalInstant() {
            if (aggregate == Aggregate.MIN || aggregate == Aggregate.MAX) {
                Instant chosen = aggregate == Aggregate.MIN ? minInstant : maxInstant;
                return new CellValue.DateTime(chosen, zone);
            }
            return new CellValue.Error(UNDEFINED_TOTAL_KEY);
        }

        private void foldNumber(BigDecimal value) {
            if (numeric == null) {
                numeric = value;
                return;
            }
            numeric = switch (aggregate) {
                case SUM, AVERAGE -> numeric.add(value);
                case MIN -> numeric.min(value);
                case MAX -> numeric.max(value);
                default -> numeric;
            };
        }

        private void foldCurrency(Currency seen) {
            if (currency == null) {
                currency = seen;
            } else if (!currency.equals(seen)) {
                currencyConflict = true;
            }
        }
    }
}
