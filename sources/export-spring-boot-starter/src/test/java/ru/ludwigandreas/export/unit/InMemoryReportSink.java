package ru.ludwigandreas.export.unit;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.api.StoredOutput;

/**
 * A sink that keeps finished files in memory, so an end-to-end test can assert on the bytes.
 *
 * <p>Holds the whole file, which is exactly what a production sink must not do - and is fine here
 * because the fixtures are small. A test that needs a million rows uses the filesystem sink, for
 * the same reason the engine streams in the first place.
 */
final class InMemoryReportSink implements ReportSink {

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    @Override
    public StoredOutput store(UUID runId, String fileName, ReportFormat format, Path file)
            throws IOException {
        byte[] content = Files.readAllBytes(file);
        String uri = runId + "/" + fileName;
        files.put(uri, content);
        return new StoredOutput(uri, content.length, "sha256-" + content.length);
    }

    @Override
    public InputStream open(String uri) throws IOException {
        byte[] content = files.get(uri);
        if (content == null) {
            throw new IOException("No stored output at " + uri);
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public void delete(String uri) {
        files.remove(uri);
    }

    /** The bytes stored under a uri, for a byte-for-byte assertion. */
    byte[] bytes(String uri) {
        return files.get(uri);
    }

    /** How many files this sink was given. */
    int size() {
        return files.size();
    }
}
