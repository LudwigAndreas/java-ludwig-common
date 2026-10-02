package ru.ludwigandreas.example.catalog.service.fileaction;

/**
 * The reject codes this import can emit, beyond the ones the module supplies.
 *
 * <p>Constants rather than inline strings, so that {@code ProductImportMessagesTest} can enumerate them and assert
 * each resolves in both locales. A code only ever written at its throw site cannot be checked against the bundle
 * without parsing the source - which is the same reason {@code FileActionProblemCodes} is a class of constants.
 */
public final class ProductImportMessages {

    /** The spreadsheet names a category the catalogue does not have. */
    public static final String UNKNOWN_CATEGORY = "catalog.import.unknown-category";

    /** A product with this SKU already exists, so the row would be a duplicate rather than a new product. */
    public static final String SKU_EXISTS = "catalog.import.sku-exists";

    /** The same SKU appears twice in the same file. */
    public static final String DUPLICATE_IN_FILE = "catalog.import.duplicate-in-file";

    private ProductImportMessages() {
    }

    /** Every code this class declares, for the bundle parity test. */
    public static java.util.List<String> all() {
        return java.util.List.of(UNKNOWN_CATEGORY, SKU_EXISTS, DUPLICATE_IN_FILE);
    }
}
