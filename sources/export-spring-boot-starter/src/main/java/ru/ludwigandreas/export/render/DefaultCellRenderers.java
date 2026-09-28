package ru.ludwigandreas.export.render;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.ludwigandreas.export.api.CellFormat;
import ru.ludwigandreas.export.api.CellRenderer;
import ru.ludwigandreas.export.api.CellValue;
import ru.ludwigandreas.export.api.RenderContext;

/**
 * The renderer a column gets when it does not declare one, and the rule the registry checks against.
 *
 * <h2>Why a column's type and its format have to agree at startup</h2>
 *
 * <p>The pairing of a value type with a {@link CellFormat} is the point where a definition can be
 * wrong in a way that nothing else catches. A {@code String} column declared {@code MONEY}, or a
 * {@code LocalDate} declared {@code NUMBER}, has two possible runtime behaviours and both are bad:
 * coerce, and the file carries a plausible wrong value; throw, and the run dies on row 700,000 of a
 * report somebody has been waiting twenty minutes for.
 *
 * <p>So {@link #supports} is the contract, the registry applies it to every column before the
 * context starts, and there is no coercion anywhere below this class. A column whose type is not in
 * the table declares its own {@link CellRenderer} and takes responsibility for the conversion - which
 * is a visible decision in the definition rather than an accident of what happened to compile.
 *
 * <h2>The two conventions worth stating</h2>
 *
 * <p><b>A percentage carries the ratio.</b> {@code 0.075} is 7.5%, not 0.075%. Excel's percent
 * format multiplies by a hundred itself and a text format does not, so a column that carried the
 * already-multiplied number would mean different things in XLSX and CSV.
 *
 * <p><b>A duration is a fraction of a day.</b> {@link CellValue} has no duration shape, and Excel
 * has no duration type - it stores an elapsed time as a number of days and presents it with an
 * {@code [h]:mm:ss} number format. Rendering to that number here is what lets a duration cell be
 * summed and averaged in the recipient's spreadsheet; a text format converts it back for display.
 */
public final class DefaultCellRenderers {

    /** Seconds in a day, the unit an elapsed time is stored in. */
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400L);

    /** Decimal places kept when dividing a duration into days; a hundredth of a second at one day. */
    private static final int DURATION_SCALE = 12;

    /** Decimal places in a millisecond count, which is how a Duration is read before scaling. */
    private static final int MILLISECOND_SCALE = 3;

    /**
     * Which value types each format kind accepts without an explicit renderer.
     *
     * <p>Deliberately short. Every entry here is a conversion with exactly one sensible reading;
     * anything with two - a {@code String} that might be a number, an {@code Integer} that might be
     * a date - is absent on purpose, and the definition says which it meant.
     */
    private static final Map<CellFormat.Kind, List<Class<?>>> SUPPORTED = Map.of(
            CellFormat.Kind.TEXT, List.of(CharSequence.class, Enum.class, UUID.class, Character.class),
            CellFormat.Kind.NUMBER, List.of(Number.class),
            CellFormat.Kind.PERCENT, List.of(Number.class),
            CellFormat.Kind.MONEY, List.of(Number.class),
            CellFormat.Kind.DATE, List.of(LocalDate.class),
            CellFormat.Kind.DATETIME,
            List.of(Instant.class, OffsetDateTime.class, ZonedDateTime.class, LocalDateTime.class),
            CellFormat.Kind.BOOLEAN, List.of(Boolean.class),
            CellFormat.Kind.DURATION, List.of(Duration.class));

    private DefaultCellRenderers() {
    }

    /**
     * Whether a column of this type and format can be rendered without declaring a renderer.
     *
     * @param format    the column's declared presentation
     * @param valueType the type its extractor yields
     * @return false when the definition has to supply its own {@link CellRenderer}
     */
    public static boolean supports(CellFormat format, Class<?> valueType) {
        if (format == null || valueType == null) {
            return false;
        }
        if (format.kind() == CellFormat.Kind.MONEY && format.currency() == null) {
            // CellFormat.moneyPerRow() says the currency travels on the value, and a bare Number
            // cannot carry one. Such a column has to render itself; see the class comment.
            return false;
        }
        Class<?> boxed = box(valueType);
        return SUPPORTED.getOrDefault(format.kind(), List.of()).stream()
                .anyMatch(supported -> supported.isAssignableFrom(boxed));
    }

    /**
     * Explains why a type and a format do not go together, for the registry's message.
     *
     * @param format the column's declared presentation
     * @return the accepted types, or a note that this format needs an explicit renderer
     */
    public static String describeSupported(CellFormat format) {
        if (format.kind() == CellFormat.Kind.MONEY && format.currency() == null) {
            return "a money column whose currency travels on the value needs an explicit CellRenderer";
        }
        return SUPPORTED.getOrDefault(format.kind(), List.of()).stream()
                .map(Class::getSimpleName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("nothing");
    }

    /**
     * The renderer for a supported type-and-format pair.
     *
     * @param format the column's declared presentation
     * @return a renderer that never returns null and never sees a null value - absence is handled by
     *         the column's {@code NullPolicy} before a renderer is consulted
     * @throws IllegalArgumentException if {@link #supports} would have returned false, which the
     *                                  registry has already ruled out by the time a run exists
     */
    public static CellRenderer<Object> resolve(CellFormat format) {
        return switch (format.kind()) {
            case TEXT -> (value, context) -> new CellValue.Text(text(value));
            case NUMBER, PERCENT -> (value, context) -> new CellValue.Number(decimal(value));
            case MONEY -> (value, context) -> new CellValue.Money(decimal(value), format.currency());
            case DATE -> (value, context) -> new CellValue.Date((LocalDate) value);
            case DATETIME -> (value, context) -> new CellValue.DateTime(instant(value, context), context.zone());
            case BOOLEAN -> (value, context) -> new CellValue.Bool((Boolean) value);
            case DURATION -> (value, context) -> new CellValue.Number(days((Duration) value));
        };
    }

    /** The zero a {@code NullPolicy.ZERO} column writes, in the shape its format calls for. */
    public static CellValue zero(CellFormat format) {
        if (format.kind() == CellFormat.Kind.MONEY && format.currency() != null) {
            return new CellValue.Money(BigDecimal.ZERO, format.currency());
        }
        return new CellValue.Number(BigDecimal.ZERO);
    }

    private static String text(Object value) {
        if (value instanceof Enum<?> constant) {
            // The constant's name, not its toString(): an enum whose toString was overridden for a
            // log line would silently change every file the column appears in. A definition that
            // wants localized status text declares a CellRenderer, which is the visible way to say so.
            return constant.name();
        }
        return value.toString();
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal exact) {
            return exact;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        // Everything else - Double, Float, BigInteger, an AtomicLong - through its string form rather
        // than BigDecimal.valueOf(double), which would carry the binary representation's noise into a
        // cell somebody sums. A report is frequently a financial document.
        return new BigDecimal(value.toString());
    }

    private static Instant instant(Object value, RenderContext context) {
        if (value instanceof Instant exact) {
            return exact;
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof ZonedDateTime zoned) {
            return zoned.toInstant();
        }
        // A LocalDateTime has no instant of its own. Interpreting it in the run's zone is the only
        // reading available here, and it is the reading that makes a stored wall-clock timestamp
        // display as itself - which is what a column of LocalDateTime almost always is.
        return ((LocalDateTime) value).atZone(context.zone()).toInstant();
    }

    private static BigDecimal days(Duration duration) {
        return BigDecimal.valueOf(duration.toMillis())
                .movePointLeft(MILLISECOND_SCALE)
                .divide(SECONDS_PER_DAY, DURATION_SCALE, RoundingMode.HALF_UP);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        // Every other primitive boxes to a java.lang.Number, which is all the table needs to know.
        return Number.class;
    }
}
