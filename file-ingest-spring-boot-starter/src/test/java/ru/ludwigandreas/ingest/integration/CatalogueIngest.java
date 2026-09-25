package ru.ludwigandreas.ingest.integration;

import ru.ludwigandreas.ingest.api.FileIngest;
import ru.ludwigandreas.ingest.api.IngestTask;
import ru.ludwigandreas.ingest.api.RecordApplier;
import ru.ludwigandreas.ingest.api.RecordParser;

/** The test service's one ingest: the two halves a service author writes, and nothing else. */
@IngestTask(CatalogueIngest.TASK)
public class CatalogueIngest implements FileIngest<CsvRecordParser.Row> {

    /** The task name, shared with the configuration block in the test's application.yml. */
    public static final String TASK = "partner-catalogue";

    private final CsvRecordParser parser = new CsvRecordParser();
    private final CatalogueApplier applier = new CatalogueApplier();

    @Override
    public String name() {
        return TASK;
    }

    @Override
    public RecordParser<CsvRecordParser.Row> parser() {
        return parser;
    }

    @Override
    public RecordApplier<CsvRecordParser.Row> applier() {
        return applier;
    }
}
