package ru.ludwigandreas.ingest.integration;

import java.util.List;
import ru.ludwigandreas.ingest.api.RecordApplier;
import ru.ludwigandreas.ingest.api.RecordApplyException;

/**
 * Maps a parsed row onto the staging table, and declares the merge into the target.
 *
 * <p>The author's half of the SQL carve-out: the merge is written here, in the service, exactly as
 * the module's documentation says it must be. The module interpolates nothing into it and passes it no
 * parameters.
 */
class CatalogueApplier implements RecordApplier<CsvRecordParser.Row> {

    /** A sku the test uses to prove that an apply failure quarantines one record and no more. */
    static final String POISON_SKU = "REJECT-ME";

    @Override
    public String stagingTable() {
        return "staging_catalogue";
    }

    @Override
    public List<String> columns() {
        return List.of("sku", "name", "price_cents");
    }

    @Override
    public Object[] toRow(CsvRecordParser.Row record) {
        if (POISON_SKU.equals(record.sku())) {
            throw new RecordApplyException(record, "this sku is rejected by the applier");
        }
        return new Object[] {record.sku(), record.name(), record.priceCents()};
    }

    @Override
    public String mergeStatement() {
        // The set-based upsert QueryDSL cannot express, and the reason the carve-out exists. One
        // statement: the target changes atomically, the parse phase ran free of its unique index, and
        // the number of affected rows is checkable.
        return """
                INSERT INTO catalogue (sku, name, price_cents)
                SELECT sku, name, price_cents FROM staging_catalogue
                ON CONFLICT (sku) DO UPDATE
                SET name = EXCLUDED.name, price_cents = EXCLUDED.price_cents
                """;
    }
}
