package ru.ludwigandreas.export.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Currency;

/**
 * One cell, after extraction and before writing: a typed value, never a rendered string.
 *
 * <h2>Why a cell is not a String</h2>
 *
 * <p>This is the decision the whole write path rests on. The obvious design is for a column's
 * extractor to produce text and for the writer to put that text in a cell, and it produces a
 * spreadsheet in which every date sorts alphabetically, every amount is left-aligned, and
 * {@code SUM} over a column of numbers returns zero. The recipient then retypes the file by hand,
 * which is the outcome the report existed to prevent.
 *
 * <p>Carrying the type through to the writer is what lets the XLSX writer put a real numeric cell
 * with a currency number format next to a real date cell with a date format, and lets the CSV
 * writer render the same value under the caller's locale without either writer guessing. The cost
 * is that a column has to declare what it produces; that cost is paid once per column, in code the
 * compiler checks.
 *
 * <p>Sealed for the reason {@code Fetcher} is sealed: every writer switches over this type
 * exhaustively, and a ninth shape contributed from outside would fall through to whatever the
 * default branch happened to do - which for a writer means a silently wrong cell rather than a
 * failure. Adding a shape here is deliberately a change to this module, because it is a change
 * every writer has to answer.
 *
 * @see CellFormat for how a value of a given shape is presented
 */
public sealed interface CellValue {

    /**
     * Free text. Written as a string cell, and passed through the formula sanitizer first.
     *
     * @param value the text; never null, use {@link Empty#INSTANCE} for absence
     */
    record Text(String value) implements CellValue {

        public Text {
            if (value == null) {
                throw new IllegalArgumentException(
                        "CellValue.Text needs a value; use CellValue.Empty.INSTANCE for absence");
            }
        }
    }

    /**
     * A plain number.
     *
     * <p>{@link BigDecimal} rather than {@code double} because a report is frequently a financial
     * document: {@code 0.1 + 0.2} rendered into a cell that an auditor later sums is a defect nobody
     * finds until the totals disagree by pennies.
     *
     * @param value the number
     */
    record Number(BigDecimal value) implements CellValue {

        public Number {
            if (value == null) {
                throw new IllegalArgumentException(
                        "CellValue.Number needs a value; use CellValue.Empty.INSTANCE for absence");
            }
        }
    }

    /**
     * An amount in a currency.
     *
     * <p>Distinct from {@link Number} because the currency belongs to the value, not to the column:
     * a report that lists transactions across markets has a currency per row, and a column-level
     * format would render all of them with the first row's symbol.
     *
     * @param amount   the amount
     * @param currency the currency the amount is denominated in
     */
    record Money(BigDecimal amount, Currency currency) implements CellValue {

        public Money {
            if (amount == null || currency == null) {
                throw new IllegalArgumentException("CellValue.Money needs both an amount and a currency");
            }
        }
    }

    /**
     * A calendar date with no time of day.
     *
     * <p>Kept separate from {@link DateTime} rather than modelled as a {@code DateTime} at midnight,
     * because a date that acquires a time of day acquires a timezone, and a delivery date rendered
     * as the previous day in another zone is the classic off-by-one of report exports.
     *
     * @param value the date
     */
    record Date(LocalDate value) implements CellValue {

        public Date {
            if (value == null) {
                throw new IllegalArgumentException(
                        "CellValue.Date needs a value; use CellValue.Empty.INSTANCE for absence");
            }
        }
    }

    /**
     * An instant, together with the zone it is to be presented in.
     *
     * <p>The zone travels with the value rather than being read from the writer's ambient default:
     * the run declares the requester's timezone, an asynchronous run executes on a server whose
     * default is UTC, and a value that picked up the writer's zone would be correct in the
     * synchronous path and an hour out in the deferred one.
     *
     * @param value the instant
     * @param zone  the zone the writer presents it in
     */
    record DateTime(Instant value, ZoneId zone) implements CellValue {

        public DateTime {
            if (value == null || zone == null) {
                throw new IllegalArgumentException("CellValue.DateTime needs both an instant and a zone");
            }
        }
    }

    /**
     * A boolean, written as a real boolean cell in formats that have one.
     *
     * @param value the flag
     */
    record Bool(boolean value) implements CellValue {
    }

    /**
     * No value. Rendered as a genuinely empty cell, not as {@code ""}, {@code "null"} or {@code "-"}.
     *
     * <p>A blank cell and a cell containing the two characters {@code ""} behave differently in every
     * spreadsheet function a recipient will apply to the column, so the distinction is kept all the
     * way to the writer. A column that wants a visible placeholder says so through its
     * {@link NullPolicy}, which produces a {@link Text} instead.
     */
    record Empty() implements CellValue {

        /** The empty cell. A singleton because a report at the design point produces millions of them. */
        public static final Empty INSTANCE = new Empty();
    }

    /**
     * A cell that could not be produced, carrying a message key rather than a message.
     *
     * <p>This is what a degraded enrichment stage writes. It exists so that degradation is visible
     * in the file itself: the alternative - leaving the cell empty - makes a partner outage
     * indistinguishable from a genuinely absent value, and the recipient acts on a report whose gaps
     * mean something other than what they assume.
     *
     * <p>The text is resolved at write time against the run's locale, like every other user-facing
     * string in this module.
     *
     * @param messageKey the bundle key for the marker text
     * @param args       message-format arguments for {@code messageKey}
     */
    record Error(String messageKey, Object... args) implements CellValue {

        private static final Object[] NO_ARGS = new Object[0];

        public Error {
            if (messageKey == null || messageKey.isBlank()) {
                throw new IllegalArgumentException("CellValue.Error needs a message key");
            }
            args = args == null ? NO_ARGS : args.clone();
        }

        @Override
        public Object[] args() {
            return args.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Error e && messageKey.equals(e.messageKey) && Arrays.equals(args, e.args);
        }

        @Override
        public int hashCode() {
            return 31 * messageKey.hashCode() + Arrays.hashCode(args);
        }

        @Override
        public String toString() {
            return "CellValue.Error(" + messageKey + Arrays.toString(args) + ")";
        }
    }
}
