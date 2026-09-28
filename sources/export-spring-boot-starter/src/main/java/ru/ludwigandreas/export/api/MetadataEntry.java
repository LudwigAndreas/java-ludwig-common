package ru.ludwigandreas.export.api;

/**
 * One line of a report's provenance, as it will appear in the file.
 *
 * <p>A list of these rather than a map, because the order is the content: a metadata sheet that
 * listed the run id after the parameters and the timestamps in whatever order a hash table happened
 * to produce would be read as carelessly assembled, and {@code Map.copyOf} makes no order guarantee
 * at all.
 *
 * <p>The label is already resolved into the run's locale and the value is already redacted, both by
 * the planner. A writer therefore never resolves a message key or decides what is personal data -
 * which is what stops the PII rules from having to be implemented once per format.
 *
 * @param label the resolved caption, such as "Requested by"
 * @param value the value, PII-redacted
 */
public record MetadataEntry(String label, String value) {

    public MetadataEntry {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("A MetadataEntry needs a label");
        }
        value = value == null ? "" : value;
    }
}
