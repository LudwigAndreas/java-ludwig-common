package ru.ludwigandreas.jira.api;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.error.JiraException;
import ru.ludwigandreas.jira.http.HttpMethod;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.http.MultipartBody;
import ru.ludwigandreas.jira.model.issue.Attachment;
import ru.ludwigandreas.jira.model.issue.AttachmentMeta;

/**
 * Attachments: upload, download, delete, and the instance settings that govern them.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#attachments()}.
 *
 * <p>Two things about Jira's attachment endpoint that this class handles so callers do not have to. The
 * upload needs the {@code X-Atlassian-Token: no-check} header, which every request from this client already
 * carries - without it Jira's XSRF filter answers 403 with a message that never mentions XSRF. And the
 * response is a JSON <em>array</em> of attachments rather than a single object, even for a single file.
 */
public final class AttachmentApi {

    private static final String ISSUE = ApiPaths.API_2 + "/issue";
    private static final String ATTACHMENT = ApiPaths.API_2 + "/attachment";

    private final JiraRestClient rest;

    public AttachmentApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /** Instance-wide attachment settings: whether attachments are on, and the per-file ceiling. */
    public AttachmentMeta meta() {
        return rest.get(ATTACHMENT + "/meta").operation("attachment.meta").as(AttachmentMeta.class);
    }

    /** Reads one attachment's metadata. */
    public Attachment get(String attachmentId) {
        return rest.get(JiraPaths.of(ATTACHMENT, attachmentId)).operation("attachment.get").as(Attachment.class);
    }

    /**
     * Uploads a file to an issue.
     *
     * @param issueKeyOrId the issue
     * @param filename the name to record on the attachment
     * @param contentType the media type, or {@code null} for {@code application/octet-stream}
     * @param content the file bytes
     * @return the created attachments, which for a single file is a single-element list
     */
    public List<Attachment> upload(String issueKeyOrId, String filename, String contentType, byte[] content) {
        MultipartBody body = MultipartBody.create().addFile("file", filename, contentType, content);
        return rest.post(JiraPaths.of(ISSUE, issueKeyOrId, "attachments"))
                .operation("attachment.upload")
                .rawBody(body.build())
                .as(new com.fasterxml.jackson.core.type.TypeReference<List<Attachment>>() { });
    }

    /**
     * Uploads a file from disk.
     *
     * @param issueKeyOrId the issue
     * @param file the file to upload
     * @return the created attachment
     */
    public Attachment upload(String issueKeyOrId, Path file) {
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (java.io.IOException e) {
            throw new JiraException("Could not read " + file + " to attach it to " + issueKeyOrId, e);
        }
        String contentType = probeContentType(file);
        List<Attachment> created = upload(issueKeyOrId, file.getFileName().toString(), contentType, content);
        if (created.isEmpty()) {
            throw new JiraException("Jira accepted the upload of " + file + " to " + issueKeyOrId
                    + " but returned no attachment; attachments may be disabled on this instance");
        }
        return created.get(0);
    }

    /**
     * Downloads an attachment's content.
     *
     * <p>Follows the {@code content} URL from the attachment metadata with the client's credentials
     * attached. That URL is authenticated like the rest of the API: fetching it with a plain HTTP client
     * and no credentials returns the login page with a 200, which is how an integration ends up writing an
     * HTML file to disk and calling it a PDF.
     *
     * @param attachment the attachment to fetch, as returned by {@link #get(String)} or an issue read
     * @return the file bytes
     */
    public byte[] download(Attachment attachment) {
        if (attachment.content() == null) {
            throw new JiraException("Attachment " + attachment.id() + " carries no content URL");
        }
        URI absolute = URI.create(attachment.content());
        URI relative = rest.baseUri().relativize(absolute);
        if (relative.isAbsolute()) {
            // Jira builds the content URL from its own configured base URL, which is not always the one
            // this client was pointed at - a reverse proxy, a renamed host, a stale setting. Sending the
            // credentials to whatever host that turns out to be is not acceptable, and quietly pasting the
            // absolute URL onto our base produces a nonsense path, so say what is actually wrong.
            throw new JiraException("Attachment " + attachment.id() + " has a content URL of " + absolute
                    + ", which is not under this client's base URL " + rest.baseUri()
                    + "; Jira's configured base URL probably differs from the one this client uses");
        }
        String path = relative.toString();
        return rest.method(HttpMethod.GET, path.startsWith("/") ? path : "/" + path)
                .operation("attachment.download")
                .header("Accept", "*/*")
                .execute()
                .body();
    }

    /** Deletes an attachment. */
    public void delete(String attachmentId) {
        rest.delete(JiraPaths.of(ATTACHMENT, attachmentId)).operation("attachment.delete").asVoid();
    }

    private static String probeContentType(Path file) {
        try {
            return Files.probeContentType(file);
        } catch (java.io.IOException unprobeable) {
            // A media type is advisory here - Jira stores whatever it is told and sniffs on download.
            return null;
        }
    }
}
