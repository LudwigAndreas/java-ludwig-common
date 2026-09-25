package ru.ludwigandreas.export.api;

/**
 * What a {@link ReportSink} recorded when it took a finished file.
 *
 * <p>The checksum is not decoration. A report is frequently a document somebody is expected to be
 * able to produce again months later - to an auditor, to a regulator, to a counterparty who
 * disagrees about a number - and the ability to say that the file being discussed is byte-for-byte
 * the file the run produced is the difference between answering that question and arguing about it.
 * It is also the cheapest possible check that an upload to a remote sink did not truncate.
 *
 * @param uri       where the sink put it, in whatever scheme the sink defines; opaque to the engine
 *                  and stored on the output row
 * @param sizeBytes the stored size
 * @param sha256    lowercase hex SHA-256 of the stored bytes
 */
public record StoredOutput(String uri, long sizeBytes, String sha256) {

    public StoredOutput {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("A StoredOutput needs a uri");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("StoredOutput size cannot be negative, was: " + sizeBytes);
        }
        if (sha256 == null || sha256.isBlank()) {
            throw new IllegalArgumentException("A StoredOutput needs a sha-256 checksum");
        }
    }
}
