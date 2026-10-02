package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;

/**
 * The two locale bundles carry the same keys, and between them every code the module can emit.
 *
 * <p>A key present in one locale and absent from the other produces a report that is half readable, which nobody
 * notices until a user of the other language opens one. The platform convention is that bundle key sets match
 * across locales; this is where that is checked for this module.
 */
class MessageBundleParityTest {

    private static final String BASE = "i18n/ludwig-file-action-messages";

    @Test
    @DisplayName("both locales carry exactly the same keys")
    void keySetsMatch() throws IOException {
        Set<String> english = keysOf(BASE + ".properties");
        Set<String> russian = keysOf(BASE + "_ru.properties");

        assertThat(russian).containsExactlyInAnyOrderElementsOf(english);
    }

    @Test
    @DisplayName("every code in FileActionProblemCodes has a message in both locales")
    void everyCodeHasAMessage() throws IOException {
        Set<String> english = keysOf(BASE + ".properties");
        Set<String> russian = keysOf(BASE + "_ru.properties");
        List<String> codes = FileActionProblemCodes.all();

        assertThat(codes).isNotEmpty();
        assertThat(english)
                .as("a code with no message renders as a raw key in a report a user reads")
                .containsAll(codes);
        assertThat(russian).containsAll(codes);
    }

    @Test
    @DisplayName("no message is blank, which would render as nothing at all")
    void noMessageIsBlank() throws IOException {
        for (String resource : List.of(BASE + ".properties", BASE + "_ru.properties")) {
            Properties properties = load(resource);
            for (String key : properties.stringPropertyNames()) {
                assertThat(properties.getProperty(key).strip())
                        .as("%s in %s", key, resource)
                        .isNotEmpty();
            }
        }
    }

    @Test
    @DisplayName("the two locales use the same placeholder set for each key")
    void placeholdersMatch() throws IOException {
        Properties english = load(BASE + ".properties");
        Properties russian = load(BASE + "_ru.properties");

        for (String key : new TreeSet<>(english.stringPropertyNames())) {
            assertThat(placeholders(russian.getProperty(key)))
                    .as("%s: a translation that drops an argument silently loses the number or the column"
                            + " name the message exists to carry", key)
                    .isEqualTo(placeholders(english.getProperty(key)));
        }
    }

    private static Set<String> placeholders(String message) {
        Set<String> found = new TreeSet<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{(\\d+)}").matcher(message);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static Set<String> keysOf(String resource) throws IOException {
        return new TreeSet<>(load(resource).stringPropertyNames());
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = MessageBundleParityTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(stream).as("%s is on the classpath", resource).isNotNull();
            // Read as UTF-8 explicitly. Properties.load(InputStream) is ISO-8859-1 by contract, which turns
            // every Cyrillic message in the _ru bundle into mojibake and would make this test pass on keys
            // while the values it is checking were already broken.
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
