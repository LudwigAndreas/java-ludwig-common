package ru.ludwigandreas.ingest.engine;

/**
 * Where a record was, and what it said, for a quarantine row.
 *
 * <p>Carried alongside the batch rather than inside the author's record type, because the author's
 * type is theirs and has no reason to know its own byte offset. Keeping the two parallel is what lets
 * a record that fails to map be quarantined with a position, which is most of what makes a quarantine
 * row actionable.
 *
 * @param ordinal    the record's index in the object, counting from zero
 * @param byteOffset the offset of its first byte
 * @param raw        its own text, untruncated; the committer applies the task's cap
 */
public record RecordPosition(long ordinal, long byteOffset, String raw) {
}
