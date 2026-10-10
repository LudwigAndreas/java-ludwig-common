package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.observability.core.BuildIdentity;
import ru.ludwigandreas.observability.core.BuildIdentityResolver;

/**
 * Build provenance is read from what the build packaged, one field at a time, and never invented.
 *
 * <p>Each test hands the resolver a class loader that sees one temporary directory and nothing
 * else - no parent - so that a {@code git.properties} which happens to be on the test classpath
 * cannot make an "absent" case pass for the wrong reason.
 */
class BuildIdentityResolverTest {

    private static final String GIT = """
            git.branch=release/1.4
            git.commit.id=c1fc5b81ecfa20e1b3418002a5ace90473d6734c
            git.commit.id.abbrev=c1fc5b8
            git.commit.time=2026-10-10T01\\:40\\:37+03\\:00
            git.dirty=false
            """;

    private static final String BUILD_INFO = """
            build.artifact=catalog
            build.group=ru.ludwigandreas
            build.time=2026-10-10T00\\:00\\:00Z
            build.version=1.4.2
            build.ci.build-number=4711
            """;

    @TempDir
    Path classpath;

    @Test
    void readsEveryFieldFromTheTwoPackagedResources() throws IOException {
        write("git.properties", GIT);
        write("META-INF/build-info.properties", BUILD_INFO);

        BuildIdentity identity = resolve(new MockEnvironment());

        assertThat(identity.commitId()).isEqualTo("c1fc5b81ecfa20e1b3418002a5ace90473d6734c");
        assertThat(identity.abbreviatedCommitId()).isEqualTo("c1fc5b8");
        assertThat(identity.branch()).isEqualTo("release/1.4");
        assertThat(identity.buildTimestamp()).isEqualTo("2026-10-10T00:00:00Z");
        assertThat(identity.ciBuildNumber()).isEqualTo("4711");
        assertThat(identity.dirty()).isFalse();
    }

    @Test
    void reportsGitFieldsAbsentWhenTheArtifactWasBuiltWithoutACheckout() throws IOException {
        // A source tarball: build-info is written, git.properties is not.
        write("META-INF/build-info.properties", BUILD_INFO);

        BuildIdentity identity = resolve(new MockEnvironment());

        assertThat(identity.commitId()).isNull();
        assertThat(identity.abbreviatedCommitId()).isNull();
        assertThat(identity.branch()).isNull();
        // Not known is not the same as clean, so this must not come back as false.
        assertThat(identity.dirty()).isNull();
        // And the half that was packaged is still reported: fields are independent.
        assertThat(identity.buildTimestamp()).isEqualTo("2026-10-10T00:00:00Z");
    }

    @Test
    void reportsEverythingAbsentWithNoResourcesAtAllAsInAnIde() {
        assertThat(resolve(new MockEnvironment())).isEqualTo(BuildIdentity.absent());
    }

    @Test
    void survivesAMalformedResourceAndTrustsNoneOfIt() throws IOException {
        // The first line is well-formed and the second is not. A resolver that kept what it had
        // parsed so far would report a branch from a file it could not finish reading.
        write("git.properties", "git.branch=master\ngit.commit.id=\\uZZZZ\n");
        write("META-INF/build-info.properties", BUILD_INFO);

        BuildIdentity identity = resolve(new MockEnvironment());

        assertThat(identity.branch()).isNull();
        assertThat(identity.commitId()).isNull();
        assertThat(identity.ciBuildNumber()).isEqualTo("4711");
    }

    @Test
    void leavesTheCiBuildNumberAbsentOnALocalBuild() throws IOException {
        write("git.properties", GIT);
        write("META-INF/build-info.properties", BUILD_INFO.replace("build.ci.build-number=4711\n", ""));

        BuildIdentity identity = resolve(new MockEnvironment());

        assertThat(identity.ciBuildNumber()).isNull();
        assertThat(identity.commitId()).isNotNull();
    }

    @Test
    void prefersAnExplicitPropertyOverTheResourceFieldByField() throws IOException {
        write("git.properties", GIT);
        write("META-INF/build-info.properties", BUILD_INFO);

        BuildIdentity identity = resolve(new MockEnvironment()
                .withProperty("ludwig.observability.build.branch", "hotfix/9")
                .withProperty("ludwig.observability.build.dirty", "true"));

        assertThat(identity.branch()).isEqualTo("hotfix/9");
        assertThat(identity.dirty()).isTrue();
        // The fields nobody overrode still come from the resource.
        assertThat(identity.abbreviatedCommitId()).isEqualTo("c1fc5b8");
    }

    @Test
    void treatsABlankPropertyAsNotSetRatherThanAsAnOverride() throws IOException {
        write("git.properties", GIT);

        BuildIdentity identity = resolve(new MockEnvironment()
                .withProperty("ludwig.observability.build.commit-id", " "));

        assertThat(identity.commitId()).isEqualTo("c1fc5b81ecfa20e1b3418002a5ace90473d6734c");
    }

    @Test
    void doesNotReadADirtyFlagItCannotUnderstandAsClean() throws IOException {
        write("git.properties", GIT.replace("git.dirty=false", "git.dirty=maybe"));

        assertThat(resolve(new MockEnvironment()).dirty()).isNull();
    }

    @Test
    void normalisesBlankComponentsToAbsent() {
        BuildIdentity identity = new BuildIdentity(" ", "", null, "\t", " ", null);

        assertThat(identity).isEqualTo(BuildIdentity.absent());
    }

    private BuildIdentity resolve(MockEnvironment environment) {
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classpath.toUri().toURL()}, null)) {
            return BuildIdentityResolver.resolve(environment, loader);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(String name, String content) throws IOException {
        Path file = classpath.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
