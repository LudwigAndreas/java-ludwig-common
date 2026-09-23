package ru.ludwigandreas.jira.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds a {@code multipart/form-data} payload in memory, for Jira's attachment endpoint.
 *
 * <p>Held in memory rather than streamed on purpose. Jira's attachment upload is not chunked and the server
 * enforces a maximum attachment size (10 MB by default) well below the point where buffering matters, so a
 * streaming implementation would add a lifecycle - who closes the stream, what happens on retry - for no
 * gain. An upload past the instance's limit is rejected by Jira with a 413;
 * {@link ru.ludwigandreas.jira.api.AttachmentApi#meta()} is what to read if that should be caught locally
 * first.
 *
 * <p>The filename is written as a quoted string with {@code "} and {@code \} escaped. A filename carrying a
 * quote would otherwise terminate the header early, which is both a corruption bug and a header-injection
 * vector when the name came from user input.
 */
public final class MultipartBody {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

    private final String boundary = "ludwig-jira-" + UUID.randomUUID();
    private final List<Part> parts = new ArrayList<>();

    private MultipartBody() {
    }

    /** A new, empty multipart payload. */
    public static MultipartBody create() {
        return new MultipartBody();
    }

    /**
     * Adds a file part.
     *
     * @param name form field name; Jira's attachment endpoint requires {@code file}
     * @param filename name recorded on the attachment
     * @param contentType media type, or {@code null} for {@code application/octet-stream}
     * @param content file bytes
     * @return this builder
     */
    public MultipartBody addFile(String name, String filename, String contentType, byte[] content) {
        parts.add(new Part(name, filename, contentType == null ? "application/octet-stream" : contentType,
                content.clone()));
        return this;
    }

    /** Adds a plain text form field. */
    public MultipartBody addField(String name, String value) {
        parts.add(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    /** Renders the parts into a request body, including the closing boundary. */
    public RequestBody build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (Part part : parts) {
                out.write(("--" + boundary).getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
                out.write(part.contentDisposition().getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
                if (part.contentType() != null) {
                    out.write(("Content-Type: " + part.contentType()).getBytes(StandardCharsets.UTF_8));
                    out.write(CRLF);
                }
                out.write(CRLF);
                out.write(part.content());
                out.write(CRLF);
            }
            out.write(("--" + boundary + "--").getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to assemble multipart body", e);
        }
        return RequestBody.of("multipart/form-data; boundary=" + boundary, out.toByteArray());
    }

    private record Part(String name, String filename, String contentType, byte[] content) {

        String contentDisposition() {
            StringBuilder header = new StringBuilder("Content-Disposition: form-data; name=\"")
                    .append(escape(name))
                    .append('"');
            if (filename != null) {
                header.append("; filename=\"").append(escape(filename)).append('"');
            }
            return header.toString();
        }

        private static String escape(String raw) {
            return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
        }
    }
}
