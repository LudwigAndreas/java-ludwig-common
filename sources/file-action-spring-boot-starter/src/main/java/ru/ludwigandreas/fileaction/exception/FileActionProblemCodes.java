package ru.ludwigandreas.fileaction.exception;

/**
 * Every problem code this module can render, in one place.
 *
 * <p>Each is a key in {@code i18n/ludwig-file-action-messages.properties} and its Russian counterpart,
 * and {@code MessageBundleParityTest} fails the build when a code here has no message in either locale.
 * Constants rather than inline strings so that the test can enumerate them: a code only ever written at
 * its throw site cannot be checked against the bundle without parsing the source.
 */
public final class FileActionProblemCodes {

    /** The prefix every code in this module shares. */
    public static final String PREFIX = "file-action.";

    /** The submitted file is larger than the action allows. */
    public static final String TOO_LARGE = PREFIX + "too-large";

    /** The file has more rows than the action allows. */
    public static final String TOO_MANY_ROWS = PREFIX + "too-many-rows";

    /** The content is not one of the formats the action accepts. */
    public static final String UNSUPPORTED_FORMAT = PREFIX + "unsupported-format";

    /** The content is a legacy BIFF {@code .xls} workbook, which this module does not read. */
    public static final String LEGACY_XLS = PREFIX + "legacy-xls";

    /** The container could not be read at all - a truncated upload, a corrupt ZIP. */
    public static final String UNREADABLE = PREFIX + "unreadable";

    /** The ZIP's compression ratio or entry count exceeds the configured ceiling. */
    public static final String SUSPICIOUS_ARCHIVE = PREFIX + "suspicious-archive";

    /** The workbook references external data. */
    public static final String EXTERNAL_REFERENCES = PREFIX + "external-references";

    /** The binding requires a column the file carries no header for. */
    public static final String MISSING_COLUMN = PREFIX + "missing-column";

    /** The file carries no header row, or none that matches anything the binding declares. */
    public static final String NO_HEADER_ROW = PREFIX + "no-header-row";

    /** The named sheet is not in the workbook. */
    public static final String SHEET_NOT_FOUND = PREFIX + "sheet-not-found";

    /** The file contains no data rows. */
    public static final String EMPTY = PREFIX + "empty";

    /** The scanner refused the file. */
    public static final String SCAN_REJECTED = PREFIX + "scan-rejected";

    /** More rows were rejected than the action's threshold allows. */
    public static final String TOO_MANY_REJECTS = PREFIX + "too-many-rejects";

    /** The submission is not in a state this request can act on. */
    public static final String WRONG_STATE = PREFIX + "wrong-state";

    /** No submission with that id, for that action. */
    public static final String NOT_FOUND = PREFIX + "not-found";

    /** The submission was never confirmed and its window has closed. */
    public static final String CONFIRM_WINDOW_CLOSED = PREFIX + "confirm-window-closed";

    /** The action does not apply anything, so there is nothing to confirm. */
    public static final String NOT_CONFIRMABLE = PREFIX + "not-confirmable";

    /** The caller does not hold the authority the action declares. */
    public static final String FORBIDDEN = PREFIX + "forbidden";

    /** No action is configured under that name. */
    public static final String UNKNOWN_ACTION = PREFIX + "unknown-action";

    /** A cell's value cannot be read as the type the row record declares. */
    public static final String CELL_NOT_COERCIBLE = PREFIX + "cell-not-coercible";

    /** A required cell is empty. */
    public static final String CELL_REQUIRED = PREFIX + "cell-required";

    /** A row has more or fewer values than the header row has columns. */
    public static final String RAGGED_ROW = PREFIX + "ragged-row";

    /** A row failed a Bean Validation constraint on the row record. */
    public static final String CONSTRAINT_VIOLATED = PREFIX + "constraint-violated";

    /**
     * Every code this class declares.
     *
     * <p>Enumerated by reflection over this class's own constants rather than written out a second time: a
     * hand-maintained list is one that goes stale the first time somebody adds a code and forgets, which is
     * exactly the failure the startup check and the bundle parity test exist to catch.
     *
     * @return every code, sorted
     */
    public static java.util.List<String> all() {
        java.util.List<String> codes = new java.util.ArrayList<>();
        for (java.lang.reflect.Field field : FileActionProblemCodes.class.getDeclaredFields()) {
            boolean constant = java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && java.lang.reflect.Modifier.isPublic(field.getModifiers())
                    && field.getType() == String.class
                    && !"PREFIX".equals(field.getName());
            if (!constant) {
                continue;
            }
            try {
                codes.add((String) field.get(null));
            } catch (IllegalAccessException impossible) {
                throw new IllegalStateException("a public static field was not readable", impossible);
            }
        }
        java.util.Collections.sort(codes);
        return java.util.List.copyOf(codes);
    }

    private FileActionProblemCodes() {
    }
}
