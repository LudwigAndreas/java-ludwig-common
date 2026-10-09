package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two locale bundles carry the same keys, with the same placeholders, and no blank message.
 *
 * <p>This service had no parity check before the inbox was added, which is a pre-existing gap rather
 * than something this change introduced - the convention that bundle key sets match across locales
 * is a platform rule and was only being honoured by hand here. The precedent is
 * {@code file-action-spring-boot-starter}'s test of the same name; this is the same check for this
 * module's bundles.
 *
 * <p>The failure it exists to catch is quiet: a key present in English and missing in Russian
 * renders as a raw key like {@code error.notification.inbox.not-found} inside an otherwise
 * translated problem response, and nobody notices until a Russian-speaking user hits that one error
 * path. The placeholder check catches the subtler version - a translation that drops {@code {0}}
 * still renders, and silently loses the id the message exists to carry.
 */
class MessageBundleParityTest {

    private static final String BASE = "i18n/notification-messages";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    @Test
    @DisplayName("both locales carry exactly the same keys")
    void keySetsMatch() throws IOException {
        Set<String> english = keysOf(BASE + ".properties");
        Set<String> russian = keysOf(BASE + "_ru.properties");

        assertThat(english).isNotEmpty();
        assertThat(russian).containsExactlyInAnyOrderElementsOf(english);
    }

    /**
     * Every exception this service raises resolves its key and its title. Both halves matter: a
     * problem response with a translated detail and an untranslated title is the shape a reader
     * notices least and trusts least.
     */
    @Test
    @DisplayName("every error key has a matching title key in both locales")
    void everyErrorHasATitle() throws IOException {
        for (String resource : List.of(BASE + ".properties", BASE + "_ru.properties")) {
            Set<String> keys = keysOf(resource);
            for (String key : keys) {
                if (key.startsWith("error.") && !key.endsWith(".title")) {
                    assertThat(keys)
                            .as("%s has no .title in %s", key, resource)
                            .contains(key + ".title");
                }
            }
        }
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
                    .as("%s: a translation that drops an argument still renders, and silently loses "
                            + "the value the message exists to carry", key)
                    .isEqualTo(placeholders(english.getProperty(key)));
        }
    }

    /**
     * The inbox's one message, named explicitly.
     *
     * <p>Not redundant with the parity check above: that one proves the two bundles agree with each
     * other, and would be equally satisfied by the key being absent from both. This proves it is
     * there at all, which is what a client receiving a raw key instead of a sentence depends on.
     */
    @Test
    @DisplayName("the inbox's not-found message exists in both locales")
    void inboxMessageExists() throws IOException {
        for (String resource : List.of(BASE + ".properties", BASE + "_ru.properties")) {
            assertThat(keysOf(resource))
                    .contains("error.notification.inbox.not-found",
                            "error.notification.inbox.not-found.title");
        }
    }

    private static Set<String> placeholders(String message) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(message);
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
            // Read as UTF-8 explicitly. Properties.load(InputStream) is ISO-8859-1 by contract, which
            // would turn every Cyrillic message into mojibake and make the placeholder comparison
            // pass while the file itself was unreadable.
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
