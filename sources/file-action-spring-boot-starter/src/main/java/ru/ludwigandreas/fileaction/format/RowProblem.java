package ru.ludwigandreas.fileaction.format;

import ru.ludwigandreas.fileaction.api.RowAddress;

/**
 * One reason one cell or one row could not be used, with enough in it to tell the user where to look.
 *
 * @param address where the problem is
 * @param code    a stable message key from {@code FileActionProblemCodes}, resolved against the module's
 *                bundle in the caller's locale when it is rendered. Never a formatted message: a reject
 *                is stored and may be read back in a different locale from the one that produced it
 * @param args    the message's arguments, in order. Strings rather than objects because these are
 *                persisted and read back, and a reject that could only be rendered by the JVM that
 *                created it is not a reject that survives a restart
 */
public record RowProblem(RowAddress address, String code, java.util.List<String> args) {

    /** Normalises the argument list and rejects a problem with no code. */
    public RowProblem {
        if (address == null) {
            throw new IllegalArgumentException("A RowProblem needs an address or the user cannot find it");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A RowProblem needs a message code");
        }
        args = args == null ? java.util.List.of() : java.util.List.copyOf(args);
    }

    /**
     * A problem with one cell.
     *
     * @param address the cell
     * @param code    the message key
     * @param args    the arguments
     * @return the problem
     */
    public static RowProblem of(RowAddress address, String code, String... args) {
        return new RowProblem(address, code, java.util.List.of(args));
    }
}
