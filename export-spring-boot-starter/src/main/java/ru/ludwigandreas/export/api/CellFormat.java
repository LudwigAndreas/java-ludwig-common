package ru.ludwigandreas.export.api;

import java.util.Currency;

/**
 * How a {@link CellValue} is presented: the column's declared shape, independent of any one writer.
 *
 * <p>A format is a value, not an enum constant, because three of the eight shapes carry a parameter
 * - a scale, a currency, a pattern - and an enum would push those onto the column as loose fields
 * that only some kinds read. As a value it is also the natural key for the XLSX style cache: two
 * columns declaring {@code money(EUR)} are the same format, compare equal, and therefore share one
 * cell style. That equality is load-bearing rather than incidental; see
 * {@code ru.ludwigandreas.export.format.xlsx} for why a per-cell style is fatal.
 *
 * <p>A format constrains but does not convert. {@link #kind()} says what a column promises to
 * produce; the engine rejects at startup a column whose value type cannot produce it, rather than
 * coercing at write time, because a coercion that happens on row 700,000 of a run is a coercion
 * nobody reviews.
 *
 * @param kind     the shape
 * @param scale    decimal places for {@link Kind#NUMBER} and {@link Kind#PERCENT}; ignored otherwise
 * @param currency the currency for a {@link Kind#MONEY} column whose rows share one; null means the
 *                 currency travels on each {@link CellValue.Money} instead
 * @param pattern  an explicit format pattern overriding the locale-derived one. Writers interpret it
 *                 in their own dialect - an Excel number format for XLSX, a
 *                 {@code java.time}/{@code DecimalFormat} pattern for text formats - so it is an
 *                 escape hatch for a single stubborn column, not the normal way to declare one
 */
public record CellFormat(Kind kind, int scale, Currency currency, String pattern) {

    /** Default decimal places when a numeric column does not say; matches the usual money scale. */
    public static final int DEFAULT_SCALE = 2;

    /** The eight presentations a column may declare. */
    public enum Kind {

        /** Free text. The only kind whose cells pass through the formula sanitizer. */
        TEXT,

        /** A number with a fixed number of decimal places. */
        NUMBER,

        /** An amount with a currency symbol and the currency's own default fraction digits. */
        MONEY,

        /**
         * A ratio presented as a percentage.
         *
         * <p>The value is the ratio, not the percentage: {@code 0.075} renders as {@code 7.5%}. The
         * other convention would make the same column mean different things in XLSX, where Excel's
         * percent format multiplies by a hundred itself, and in CSV, where nothing does.
         */
        PERCENT,

        /** A calendar date with no time of day. */
        DATE,

        /** An instant presented in the run's timezone. */
        DATETIME,

        /** A boolean, written as a real boolean cell where the format has one. */
        BOOLEAN,

        /** An elapsed duration, presented as hours, minutes and seconds rather than as a count. */
        DURATION
    }

    public CellFormat {
        if (kind == null) {
            throw new IllegalArgumentException("A CellFormat needs a kind");
        }
        if (scale < 0) {
            throw new IllegalArgumentException("CellFormat scale cannot be negative, was: " + scale);
        }
        if (currency != null && kind != Kind.MONEY) {
            throw new IllegalArgumentException(
                    "CellFormat currency is only meaningful for MONEY, was set on: " + kind);
        }
    }

    /** Free text. */
    public static CellFormat text() {
        return new CellFormat(Kind.TEXT, 0, null, null);
    }

    /** A number with the given number of decimal places. */
    public static CellFormat number(int scale) {
        return new CellFormat(Kind.NUMBER, scale, null, null);
    }

    /** An amount in a currency fixed for the whole column. */
    public static CellFormat money(Currency currency) {
        if (currency == null) {
            throw new IllegalArgumentException("CellFormat.money needs a currency; use moneyPerRow() otherwise");
        }
        return new CellFormat(Kind.MONEY, currency.getDefaultFractionDigits(), currency, null);
    }

    /**
     * An amount whose currency travels on each value.
     *
     * <p>The right choice for a column that spans markets. It costs one style per currency in the
     * XLSX style cache rather than one for the column, which is why it is not the default.
     */
    public static CellFormat moneyPerRow() {
        return new CellFormat(Kind.MONEY, DEFAULT_SCALE, null, null);
    }

    /** A ratio presented as a percentage; the value is the ratio. */
    public static CellFormat percent(int scale) {
        return new CellFormat(Kind.PERCENT, scale, null, null);
    }

    /** A calendar date. */
    public static CellFormat date() {
        return new CellFormat(Kind.DATE, 0, null, null);
    }

    /** An instant presented in the run's timezone. */
    public static CellFormat dateTime() {
        return new CellFormat(Kind.DATETIME, 0, null, null);
    }

    /** A boolean. */
    public static CellFormat bool() {
        return new CellFormat(Kind.BOOLEAN, 0, null, null);
    }

    /** An elapsed duration. */
    public static CellFormat duration() {
        return new CellFormat(Kind.DURATION, 0, null, null);
    }

    /** This format with an explicit writer-dialect pattern; see {@link #pattern()}. */
    public CellFormat withPattern(String explicitPattern) {
        return new CellFormat(kind, scale, currency, explicitPattern);
    }

    /** Whether cells of this format carry text a spreadsheet could interpret as a formula. */
    public boolean isTextual() {
        return kind == Kind.TEXT;
    }

    /** Whether cells of this format participate in a numeric aggregate. */
    public boolean isNumeric() {
        return kind == Kind.NUMBER || kind == Kind.MONEY || kind == Kind.PERCENT;
    }
}
