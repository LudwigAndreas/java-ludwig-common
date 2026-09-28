package ru.ludwigandreas.testsupport.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.testsupport.image.LudwigTestImages;

/**
 * The one property of this module worth a unit test: that every pin carries a digest.
 *
 * <p>Everything else here - the containers, the fixtures, the cleaner - is exercised by the eleven
 * modules that use it, and a test that started a container to prove a container starts would be
 * slower and prove less. This is different: it is the guard that stops the module from silently
 * reintroducing the policy violation it exists to prevent, and nothing downstream would fail if it
 * broke. A bare tag in images.properties would give every module a working container on an image
 * nobody chose, and every suite would still be green.
 */
class ImagePinningTest {

    @Test
    @DisplayName("every pin in images.properties carries a name and a 64-hex sha256 digest")
    void everyPinCarriesADigest() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = LudwigTestImages.class.getResourceAsStream("/images.properties")) {
            assertThat(stream).as("images.properties must ship inside this jar").isNotNull();
            properties.load(stream);
        }

        List<String> keys = new ArrayList<>(properties.stringPropertyNames());
        assertThat(keys)
                .as("the resource must actually pin something, or this test passes vacuously")
                .isNotEmpty();

        for (String key : keys) {
            assertThat(key).startsWith("ludwig.test.image.");
            assertThat(properties.getProperty(key).trim())
                    .as("%s must be pinned by name, version and digest - a tag alone can be "
                            + "re-pointed at different content", key)
                    .matches("\\S+@sha256:[0-9a-f]{64}");
        }
    }

    @Test
    @DisplayName("the three images the platform uses are exposed as constants")
    void constantsResolve() {
        assertThat(LudwigTestImages.POSTGRES.asCanonicalNameString()).contains("postgres");
        assertThat(LudwigTestImages.KAFKA.asCanonicalNameString()).contains("kafka");
        assertThat(LudwigTestImages.LOCALSTACK.asCanonicalNameString()).contains("localstack");
    }

    @Test
    @DisplayName("an unknown key fails loudly rather than returning null")
    void unknownKeyFails() {
        assertThatThrownBy(() -> LudwigTestImages.reference("mysql"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ludwig.test.image.mysql");
    }

    @Test
    @DisplayName("the digest-pinned name is still recognised as its base image")
    void digestPinnedNameIsSubstitutable() {
        // The reason asCompatibleSubstituteFor is applied centrally: Testcontainers refuses an image
        // whose name it cannot recognise as the one a container class expects, and a name carrying a
        // digest never parses as a plain repository. One call site used to forget this.
        assertThat(LudwigTestImages.POSTGRES.isCompatibleWith(
                DockerImageName.parse("postgres"))).isTrue();
    }
}
