package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A link type, with both directions named: {@code inward} ("is blocked by") and {@code outward} ("blocks").
 *
 * <p>A link is created by naming the type plus which issue is the inward and which the outward end, so
 * getting the two the wrong way round produces a link that reads backwards in the UI. There is no way to
 * detect that afterwards from the data - the link is valid either way.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IssueLinkType(String self, String id, String name, String inward, String outward) {

    /** A reference by name, which is how a link-creation payload identifies the type. */
    public static IssueLinkType named(String name) {
        return new IssueLinkType(null, null, name, null, null);
    }

    /** A reference by id. */
    public static IssueLinkType byId(String id) {
        return new IssueLinkType(null, id, null, null, null);
    }
}
