package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.SimpleObjectWrapper;
import freemarker.template.TemplateExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.exception.TemplateNotFoundException;
import ru.ludwigandreas.notification.service.exception.TemplateRenderException;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.template.FreemarkerTemplateRenderer;
import ru.ludwigandreas.notification.service.template.RenderedTemplate;
import ru.ludwigandreas.notification.service.template.TemplateCoordinates;
import ru.ludwigandreas.notification.service.template.TemplateRenderer;
import ru.ludwigandreas.notification.service.template.TemplateSourceCatalog;

/**
 * Rendering, and specifically the strictness that is the whole point of it.
 *
 * <p>Driven through a {@link StringTemplateLoader} rather than the file system, so the fallback chain
 * is exercised by declaring exactly which template names exist - which is the thing being tested.
 */
class TemplateRenderingTest {

    private StringTemplateLoader templates;
    private TemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        templates = new StringTemplateLoader();
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_32);
        configuration.setTemplateLoader(templates);
        configuration.setObjectWrapper(new SimpleObjectWrapper(Configuration.VERSION_2_3_32));
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLocalizedLookup(false);
        configuration.setLogTemplateExceptions(false);

        NotificationProperties.Templates properties = new NotificationProperties.Templates();
        properties.setDefaultLocale("en");

        renderer = new FreemarkerTemplateRenderer(configuration,
                new TemplateSourceCatalog(templates), properties);
    }

    /**
     * The failure this service exists to avoid. FreeMarker can be configured so a missing variable
     * renders as an empty string - and then a password-reset email goes out reading "your code is .",
     * accepted by the provider, counted as a success, and discovered days later by a customer.
     */
    @Test
    @DisplayName("a missing variable fails loudly and names the expression that failed")
    void missingVariableIsARenderFailure() {
        put("password-reset/email/en/body.html.ftl", "Your code is ${code}.");

        assertThatThrownBy(() -> renderer.render(coordinates("password-reset", Locale.ENGLISH), Map.of()))
                .isInstanceOf(TemplateRenderException.class)
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("a template that opts into a default renders it")
    void explicitDefaultIsHonoured() {
        put("welcome/email/en/body.html.ftl", "Hello ${name!\"there\"}.");

        assertThat(renderer.render(coordinates("welcome", Locale.ENGLISH), Map.of()).htmlBody())
                .isEqualTo("Hello there.");
    }

    @Test
    @DisplayName("an absent template for every locale is a not-found, not a render failure")
    void missingTemplateIsNotFound() {
        assertThatThrownBy(() -> renderer.render(coordinates("nothing-here", Locale.ENGLISH), Map.of()))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    @Test
    @DisplayName("the exact locale wins when it exists")
    void exactLocaleWins() {
        put("welcome/email/en/body.html.ftl", "English");
        put("welcome/email/ru/body.html.ftl", "Russian");

        RenderedTemplate rendered = renderer.render(
                coordinates("welcome", Locale.forLanguageTag("ru")), Map.of());

        assertThat(rendered.htmlBody()).isEqualTo("Russian");
        assertThat(rendered.resolvedLocale()).isEqualTo("ru");
    }

    @Test
    @DisplayName("a regional locale falls back to its language before the default")
    void regionFallsBackToLanguage() {
        put("welcome/email/en/body.html.ftl", "English");
        put("welcome/email/ru/body.html.ftl", "Russian");

        RenderedTemplate rendered = renderer.render(
                coordinates("welcome", Locale.forLanguageTag("ru-RU")), Map.of());

        assertThat(rendered.htmlBody()).isEqualTo("Russian");
        assertThat(rendered.resolvedLocale()).isEqualTo("ru");
    }

    /**
     * Reporting which locale was actually used is the single most useful thing the preview endpoint
     * does: a missing translation otherwise surfaces only as a customer receiving English.
     */
    @Test
    @DisplayName("an untranslated locale falls back to the default and says so")
    void untranslatedFallsBackToDefault() {
        put("welcome/email/en/body.html.ftl", "English");

        RenderedTemplate rendered = renderer.render(
                coordinates("welcome", Locale.forLanguageTag("fr")), Map.of());

        assertThat(rendered.htmlBody()).isEqualTo("English");
        assertThat(rendered.resolvedLocale()).isEqualTo("en");
    }

    @Test
    @DisplayName("the subject and the text alternative are optional, the body is not")
    void optionalParts() {
        put("welcome/email/en/body.html.ftl", "<p>Hi</p>");

        RenderedTemplate rendered = renderer.render(coordinates("welcome", Locale.ENGLISH), Map.of());

        assertThat(rendered.htmlBody()).isEqualTo("<p>Hi</p>");
        assertThat(rendered.subject()).isNull();
        assertThat(rendered.textBody()).isNull();
    }

    @Test
    @DisplayName("all three parts render from the same model")
    void rendersEveryPart() {
        put("welcome/email/en/subject.ftl", "  Welcome, ${name}  ");
        put("welcome/email/en/body.html.ftl", "<p>Welcome, ${name}</p>");
        put("welcome/email/en/body.txt.ftl", "Welcome, ${name}");

        RenderedTemplate rendered = renderer.render(
                coordinates("welcome", Locale.ENGLISH), Map.of("name", "Ada"));

        // The subject is stripped: a trailing newline in the file would otherwise become a header
        // continuation, which some mail servers reject outright.
        assertThat(rendered.subject()).isEqualTo("Welcome, Ada");
        assertThat(rendered.htmlBody()).isEqualTo("<p>Welcome, Ada</p>");
        assertThat(rendered.textBody()).isEqualTo("Welcome, Ada");
    }

    /**
     * A template needs to know which language it ended up as, and it cannot derive that from the
     * variables - the fallback may have chosen a different language from the one requested.
     */
    @Test
    @DisplayName("the resolved locale is exposed to the template")
    void localeIsInTheModel() {
        put("welcome/email/en/body.html.ftl", "lang=${locale}");

        assertThat(renderer.render(coordinates("welcome", Locale.forLanguageTag("fr")), Map.of())
                .htmlBody()).isEqualTo("lang=en");
    }

    /**
     * The hash is what ties a sent message to exact text after the file has been edited twice, so it
     * has to change when and only when the source does.
     */
    @Test
    @DisplayName("the template version is the hash of the source that rendered")
    void versionTracksTheSource() {
        put("welcome/email/en/body.html.ftl", "v1");
        String first = renderer.render(coordinates("welcome", Locale.ENGLISH), Map.of()).templateVersion();

        put("welcome/email/en/body.html.ftl", "v2");
        String second = renderer.render(coordinates("welcome", Locale.ENGLISH), Map.of()).templateVersion();

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    @Test
    @DisplayName("the channel is part of the address, so one key renders differently per channel")
    void channelIsPartOfTheAddress() {
        put("welcome/email/en/body.html.ftl", "<p>Long form</p>");
        put("welcome/chat/en/body.html.ftl", "Short form");

        assertThat(renderer.render(
                new TemplateCoordinates("welcome", ChannelType.CHAT, Locale.ENGLISH), Map.of()).htmlBody())
                .isEqualTo("Short form");
    }

    @Test
    @DisplayName("the candidate chain is most-specific-first and does not repeat the default")
    void candidateChain() {
        List<String> candidates = new TemplateCoordinates("welcome", ChannelType.EMAIL,
                Locale.forLanguageTag("ru-RU")).candidates(TemplateCoordinates.BODY_HTML, "en");

        assertThat(candidates).containsExactly(
                "welcome/email/ru-ru/body.html.ftl",
                "welcome/email/ru/body.html.ftl",
                "welcome/email/en/body.html.ftl");
    }

    @Test
    @DisplayName("asking for the default locale produces one candidate, not three identical ones")
    void candidateChainDeduplicates() {
        List<String> candidates = new TemplateCoordinates("welcome", ChannelType.EMAIL, Locale.ENGLISH)
                .candidates(TemplateCoordinates.BODY_HTML, "en");

        assertThat(candidates).containsExactly("welcome/email/en/body.html.ftl");
    }

    private void put(String name, String source) {
        templates.putTemplate(name, source);
    }

    private static TemplateCoordinates coordinates(String key, Locale locale) {
        return new TemplateCoordinates(key, ChannelType.EMAIL, locale);
    }
}
