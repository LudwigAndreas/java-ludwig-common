package ru.ludwigandreas.storage.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.exception.InvalidObjectUriException;

/**
 * The parser is the one place a location is interpreted, so it is the one place these mistakes can
 * be caught - which is why the rejections are tested as carefully as the acceptances.
 */
class ObjectUriTest {

    @Test
    void parsesAnS3Location() {
        ObjectUri uri = ObjectUri.parse("s3://partner-drop/catalogue/2026-09-25.csv.gz");

        assertThat(uri.isS3()).isTrue();
        assertThat(uri.container()).isEqualTo("partner-drop");
        assertThat(uri.key()).isEqualTo("catalogue/2026-09-25.csv.gz");
        assertThat(uri.name()).isEqualTo("2026-09-25.csv.gz");
    }

    @Test
    void parsesABucketWithNoKeyAsAListablePrefix() {
        ObjectUri uri = ObjectUri.parse("s3://partner-drop");

        assertThat(uri.container()).isEqualTo("partner-drop");
        assertThat(uri.key()).isEmpty();
        assertThat(uri.value()).isEqualTo("s3://partner-drop");
    }

    @Test
    void parsesAFileLocation() {
        ObjectUri uri = ObjectUri.parse("file:///var/drop/catalogue.csv");

        assertThat(uri.isS3()).isFalse();
        assertThat(uri.container()).isEmpty();
        assertThat(uri.key()).isEqualTo("/var/drop/catalogue.csv");
        assertThat(uri.name()).isEqualTo("catalogue.csv");
    }

    @Test
    void rendersACanonicalFormThatParsesBackToAnEqualLocation() {
        for (String candidate : new String[] {
                "s3://bucket/a/b/c.txt", "s3://bucket", "s3://bucket/", "file:///tmp/a.txt"}) {
            ObjectUri parsed = ObjectUri.parse(candidate);
            assertThat(ObjectUri.parse(parsed.value())).isEqualTo(parsed);
        }
    }

    @Test
    void derivesASiblingWithoutLosingTheBucket() {
        ObjectUri sentinel = ObjectUri.parse("s3://drop/in/data.csv").withKey("in/data.csv.done");

        assertThat(sentinel.value()).isEqualTo("s3://drop/in/data.csv.done");
    }

    @Test
    void stripsLeadingSlashesFromAKeyBecauseS3KeysDoNotHaveThem() {
        assertThat(ObjectUri.ofS3("drop", "/a/b.txt").key()).isEqualTo("a/b.txt");
    }

    @Test
    void makesARelativePathAbsoluteBecauseARelativeFileUriHasNoMeaning() {
        ObjectUri uri = ObjectUri.ofFile(Paths.get("relative", "file.txt"));

        assertThat(uri.key()).isEqualTo(
                Paths.get("relative", "file.txt").toAbsolutePath().normalize().toString());
    }

    /**
     * Each of these is a mistake that would otherwise be discovered by its consequence: a bare path
     * read as a relative file, a Windows path read as a scheme, a traversal resolved against a
     * storage root, a {@code file:} location naming another machine.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "partner-drop/catalogue.csv",
            "/var/drop/catalogue.csv",
            "C:\\drop\\catalogue.csv",
            "gs://bucket/key",
            "https://example.test/object",
            "s3:///no-bucket/key",
            "s3://bucket/../../etc/passwd",
            "file://otherhost/var/drop/catalogue.csv"})
    void rejectsWhatItCannotServe(String candidate) {
        assertThatThrownBy(() -> ObjectUri.parse(candidate))
                .isInstanceOf(InvalidObjectUriException.class);
    }

    @Test
    void rejectsATraversalAssembledInCodeAsWellAsOneThatWasParsed() {
        assertThatThrownBy(() -> new ObjectUri("s3", "bucket", "a/../../b"))
                .isInstanceOf(InvalidObjectUriException.class);
    }
}
