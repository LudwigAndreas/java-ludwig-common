package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import ru.ludwigandreas.jira.model.field.FieldMeta;

/**
 * {@code GET /rest/api/2/issue/createmeta}: which projects and issue types the caller may create in, and -
 * when expanded - which fields each combination requires.
 *
 * <p>Always fetch this scoped to the project and issue type you are about to use. Unscoped and fully
 * expanded, on an instance with a few hundred projects, this single call routinely returns tens of
 * megabytes and takes minutes; {@link ru.ludwigandreas.jira.api.IssueApi#createMeta} therefore requires the
 * scope rather than offering an unscoped convenience.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateMeta(String expand, List<CreateMetaProject> projects) {

    /** Normalizes {@code projects} to an immutable empty list rather than {@code null}. */
    public CreateMeta {
        projects = projects == null ? List.of() : List.copyOf(projects);
    }

    /** One project the caller may create issues in. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateMetaProject(String expand,
                                    String self,
                                    String id,
                                    String key,
                                    String name,
                                    Map<String, String> avatarUrls,
                                    List<CreateMetaIssueType> issuetypes) {

        /** Normalizes {@code issuetypes} to an immutable empty list rather than {@code null}. */
        public CreateMetaProject {
            issuetypes = issuetypes == null ? List.of() : List.copyOf(issuetypes);
        }
    }

    /** One issue type within a project, with its create screen's fields when expanded. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateMetaIssueType(String self,
                                      String id,
                                      String name,
                                      String description,
                                      String iconUrl,
                                      Boolean subtask,
                                      String expand,
                                      Map<String, FieldMeta> fields) {

        /** Normalizes {@code fields} to an immutable empty map rather than {@code null}. */
        public CreateMetaIssueType {
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }
}
