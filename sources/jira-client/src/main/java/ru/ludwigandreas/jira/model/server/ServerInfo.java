package ru.ludwigandreas.jira.model.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /rest/api/2/serverInfo}: which Jira this client is actually talking to.
 *
 * <p>Worth calling once at startup and logging. This client targets
 * {@code version 9.12.27, buildNumber 9120027}, and the two places behaviour diverges by version -
 * personal access tokens (8.14+) and the {@code deploymentType} that distinguishes Server/Data Center from
 * Cloud - are both answered here. A {@code deploymentType} of {@code Cloud} means the base URL is pointed
 * at a Jira Cloud site, where this client's {@code /rest/api/2} calls will mostly work and its username-based
 * user handling will not.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerInfo(String baseUrl,
                         String version,
                         List<Integer> versionNumbers,
                         Integer deploymentType,
                         Integer buildNumber,
                         OffsetDateTime buildDate,
                         OffsetDateTime serverTime,
                         String scmInfo,
                         String serverTitle) {

    /** Normalizes {@code versionNumbers} to an immutable empty list rather than {@code null}. */
    public ServerInfo {
        versionNumbers = versionNumbers == null ? List.of() : List.copyOf(versionNumbers);
    }

    /** The build number this client was written and verified against. */
    public static final int TARGET_BUILD_NUMBER = 9120027;

    /** Whether the instance is at least as new as the build this client targets. */
    public boolean isAtLeastTargetBuild() {
        return buildNumber != null && buildNumber >= TARGET_BUILD_NUMBER;
    }
}
