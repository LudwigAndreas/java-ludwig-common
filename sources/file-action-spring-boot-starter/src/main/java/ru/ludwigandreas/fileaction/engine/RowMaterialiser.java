package ru.ludwigandreas.fileaction.engine;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.ludwigandreas.fileaction.api.ColumnBinding;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.format.CellCoercion;
import ru.ludwigandreas.fileaction.format.Coerced;
import ru.ludwigandreas.fileaction.format.CellValue;
import ru.ludwigandreas.fileaction.format.CoercionContext;
import ru.ludwigandreas.fileaction.format.RawRow;
import ru.ludwigandreas.fileaction.format.RowProblem;

/**
 * Turns one {@link RawRow} into one row record, or into the reasons it could not be.
 *
 * <h2>Every problem with a row is collected, not the first one</h2>
 *
 * <p>Stopping at the first bad cell means a user fixes one cell, re-uploads, and is told about the next -
 * four times, for a row with four problems. The whole value of an import error report is that one pass
 * through it fixes the file, so every cell of every row is coerced even after one has failed.
 *
 * <p>Bean Validation runs only when the record could be constructed at all, because a constraint on a
 * field whose value would not coerce has nothing to say that the coercion failure has not already said
 * better.
 *
 * @param <R> the row record type
 */
public final class RowMaterialiser<R> {

    private final RowBinding<R> binding;
    private final Validator validator;
    private final Constructor<R> canonical;
    private final RecordComponent[] components;
    private final Map<String, ColumnBinding> columnByField;

    /**
     * Prepares a materialiser for one binding.
     *
     * <p>The reflection happens once, here, rather than per row: resolving a record's canonical constructor
     * a hundred thousand times is measurable, and it cannot fail per row - either the binding matches the
     * record or it never will, which {@code RowBinding} has already checked at startup.
     *
     * @param binding   the binding
     * @param validator the Bean Validation validator, or null when the application has none, in which case
     *                  constraints on the row record are not checked and the module says so at startup
     *                  rather than silently skipping them
     */
    public RowMaterialiser(RowBinding<R> binding, Validator validator) {
        this.binding = binding;
        this.validator = validator;
        this.components = binding.rowType().getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            parameterTypes[i] = components[i].getType();
        }
        try {
            this.canonical = binding.rowType().getDeclaredConstructor(parameterTypes);
            this.canonical.setAccessible(true);
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException(
                    "every record has a canonical constructor, but " + binding.rowType().getName()
                            + " appears not to", impossible);
        }
        Map<String, ColumnBinding> byField = new LinkedHashMap<>();
        for (ColumnBinding column : binding.columns()) {
            byField.put(column.field(), column);
        }
        this.columnByField = Map.copyOf(byField);
    }

    /**
     * Materialises one row.
     *
     * @param row     the raw row
     * @param context the caller's locale and zone, and the workbook's date epoch
     * @return the row record, or the problems that stopped it being built
     */
    public Result<R> materialise(RawRow row, CoercionContext context) {
        List<RowProblem> problems = new ArrayList<>();
        for (String code : row.problems()) {
            problems.add(RowProblem.of(row.address(), code));
        }
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            ColumnBinding column = columnByField.get(component.getName());
            if (column == null) {
                // A component the binding does not map. Left at its type's zero value, which for a record
                // means null for a reference and 0 for a primitive - the author declared a field the file
                // does not carry, which is their business and not a row problem.
                values[i] = null;
                continue;
            }
            CellValue cell = row.cell(column.header());
            if (cell.isEmpty()) {
                if (component.getType().isPrimitive()) {
                    // A primitive has no null. An empty cell for one is a problem rather than a zero,
                    // because silently importing 0 for a quantity the user left blank is worse than
                    // refusing the row.
                    problems.add(RowProblem.of(row.address(column.header()),
                            FileActionProblemCodes.CELL_REQUIRED, column.header()));
                }
                values[i] = null;
                continue;
            }
            Coerced coerced = CellCoercion.coerce(cell, component.getType(), context);
            if (!coerced.coercible()) {
                problems.add(RowProblem.of(row.address(column.header()),
                        FileActionProblemCodes.CELL_NOT_COERCIBLE, column.header(),
                        cell.trimmedText(), component.getType().getSimpleName()));
                values[i] = null;
                continue;
            }
            values[i] = coerced.value();
        }
        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        R instance;
        try {
            instance = canonical.newInstance(values);
        } catch (InvocationTargetException refusedByTheRecord) {
            // A record's own compact constructor threw - the author validated something there. Treated as a
            // row problem rather than a failure, because that is exactly what it is: this row is not
            // acceptable and the next one may well be.
            problems.add(RowProblem.of(row.address(), FileActionProblemCodes.CONSTRAINT_VIOLATED,
                    messageOf(refusedByTheRecord.getTargetException())));
            return Result.rejected(problems);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(
                    "the canonical constructor of " + binding.rowType().getName()
                            + " could not be invoked", impossible);
        }
        if (validator != null) {
            for (ConstraintViolation<R> violation : validator.validate(instance)) {
                String field = violation.getPropertyPath().toString();
                ColumnBinding column = columnByField.get(field);
                problems.add(RowProblem.of(
                        column == null ? row.address() : row.address(column.header()),
                        FileActionProblemCodes.CONSTRAINT_VIOLATED,
                        column == null ? field : column.header(),
                        violation.getMessage()));
            }
        }
        return problems.isEmpty() ? Result.bound(instance) : Result.rejected(problems);
    }

    private static String messageOf(Throwable thrown) {
        String message = thrown.getMessage();
        return message == null ? thrown.getClass().getSimpleName() : message;
    }

    /**
     * Either a bound row or the reasons there is not one.
     *
     * @param row      the bound row, or null when it was rejected
     * @param problems why it was rejected; empty when it was not
     * @param <R>      the row record type
     */
    public record Result<R>(R row, List<RowProblem> problems) {

        /** Defensively copies the problem list. */
        public Result {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** A row that bound cleanly. */
        static <R> Result<R> bound(R row) {
            return new Result<>(row, List.of());
        }

        /** A row that did not. */
        static <R> Result<R> rejected(List<RowProblem> problems) {
            return new Result<>(null, problems);
        }

        /** Whether there is a row to apply. */
        public boolean isBound() {
            return row != null;
        }
    }
}
