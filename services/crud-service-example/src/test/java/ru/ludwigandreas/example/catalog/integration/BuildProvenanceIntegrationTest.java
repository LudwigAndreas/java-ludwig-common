package ru.ludwigandreas.example.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two provenance resources {@code ludwig-service-parent} promises are really on this service's
 * classpath, and the git one really names a commit.
 *
 * <p>This is the check on the <em>outcome</em>. {@code scripts/check_build_metadata.sh} checks the
 * configuration, costs milliseconds and names the setting that went missing; it cannot tell
 * configuration that looks right from configuration that produces nothing, and that is precisely the
 * defect this test exists for. The service parent once documented that it wrote
 * {@code git.properties} into the jar next to a plugin block that set nothing of the kind, and a
 * version fallback read a {@code build.version} that no goal was bound to write. Both read as true
 * for as long as nothing looked at the artifact.
 *
 * <p>No Spring context and no container: the claim is about what the build put on the classpath, and
 * starting an application to read two files would only add ways for the test to fail for another
 * reason.
 *
 * <p>Named {@code ...IntegrationTest} so that failsafe runs it at {@code verify}; surefire excludes
 * the name. A build from a source tree with no git checkout has no {@code git.properties} by
 * design, so it cannot pass this test and should not run it - that build is for packaging, and the
 * resource being absent there is the documented behaviour rather than a regression.
 */
class BuildProvenanceIntegrationTest {

    @Test
    @DisplayName("git.properties is packaged and carries the commit this artifact was built from")
    void gitProvenanceIsPackaged() throws IOException {
        Properties git = load("git.properties");

        assertThat(git.getProperty("git.commit.id")).as("git.commit.id").matches("[0-9a-f]{40}");
        assertThat(git.getProperty("git.commit.id.abbrev")).as("git.commit.id.abbrev").isNotBlank();
        assertThat(git.getProperty("git.commit.id")).startsWith(git.getProperty("git.commit.id.abbrev"));
        assertThat(git.getProperty("git.branch")).as("git.branch").isNotNull();
        assertThat(git.getProperty("git.commit.time")).as("git.commit.time").isNotBlank();
        assertThat(git.getProperty("git.dirty")).as("git.dirty").isIn("true", "false");
    }

    @Test
    @DisplayName("git.properties carries the five provenance keys and nothing about who built it or where")
    void gitProvenanceIsNarrowedToTheAllowList() throws IOException {
        // Unfiltered, the plugin also writes the committer's e-mail, the build host and the remote
        // URL - and /actuator/info serves whatever is in this file.
        assertThat(load("git.properties").stringPropertyNames()).containsExactlyInAnyOrder(
                "git.commit.id", "git.commit.id.abbrev", "git.branch", "git.commit.time", "git.dirty");
    }

    @Test
    @DisplayName("build-info.properties is packaged with the coordinates and a day-precision build time")
    void buildInfoIsPackaged() throws IOException {
        Properties buildInfo = load("META-INF/build-info.properties");

        assertThat(buildInfo.getProperty("build.group")).isEqualTo("ru.ludwigandreas");
        assertThat(buildInfo.getProperty("build.artifact")).isEqualTo("crud-service-example");
        assertThat(buildInfo.getProperty("build.name")).isNotBlank();
        assertThat(buildInfo.getProperty("build.version")).isNotBlank();
        // Truncated to the day so that two builds of one commit share an image digest. Parsed as
        // well as matched, because the runtime reads it as an instant.
        String time = buildInfo.getProperty("build.time");
        assertThat(time).as("build.time").endsWith("T00:00:00Z");
        assertThat(Instant.parse(time)).isBeforeOrEqualTo(Instant.now());
    }

    private Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(stream)
                    .as("%s on the classpath - ludwig-service-parent is expected to generate it", name)
                    .isNotNull();
            properties.load(stream);
        }
        return properties;
    }
}
