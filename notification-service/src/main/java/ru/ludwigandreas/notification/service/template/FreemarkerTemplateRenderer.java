package ru.ludwigandreas.notification.service.template;

import freemarker.core.InvalidReferenceException;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.exception.TemplateNotFoundException;
import ru.ludwigandreas.notification.service.exception.TemplateRenderException;
import ru.ludwigandreas.notification.service.model.ChannelType;

/**
 * Renders through FreeMarker, strictly.
 *
 * <h2>Strictness is the feature</h2>
 *
 * <p>FreeMarker can be configured so that {@code ${firstName}} on a missing variable produces an
 * empty string. That configuration is a trap. The password-reset email goes out reading "Hello ,
 * your code is ." - accepted by the provider, delivered to the mailbox, counted as a success on
 * every dashboard, and discovered days later by a customer who could not log in. So the default
 * behaviour, which raises {@link InvalidReferenceException}, is kept and the exception is turned into
 * a {@link TemplateRenderException} that names the expression that failed. A render that cannot be
 * completed is a rejected request, loudly.
 *
 * <p>Two consequences follow, both deliberate. A template that genuinely has an optional field must
 * say so with {@code ${middleName!""}} - opting into the empty string at the one place it is correct.
 * And because fan-out renders nothing, a missing variable surfaces at dispatch: the delivery fails
 * terminally and goes {@code DEAD} rather than retrying eight times against a payload that will never
 * contain the field. See {@code DeliveryDispatchService}.
 *
 * <h2>Why templates are parsed here rather than by {@code Configuration#getTemplate}</h2>
 *
 * <p>The source is read through {@link TemplateSourceCatalog} anyway, to hash it - and hashing is
 * what gives every sent message a traceable version. Parsing the text we already hold keeps the two
 * in step by construction: it is impossible for the recorded version to describe a different text
 * from the one that rendered. Going through the {@code Configuration}'s own cache would mean the hash
 * and the render could come from two different reads of a file that changed in between, which is
 * precisely the case the versioning exists to make legible.
 */
@Slf4j
public class FreemarkerTemplateRenderer implements TemplateRenderer {

    private final Configuration configuration;
    private final TemplateSourceCatalog catalog;
    private final NotificationProperties.Templates properties;

    public FreemarkerTemplateRenderer(Configuration configuration,
                                      TemplateSourceCatalog catalog,
                                      NotificationProperties.Templates properties) {
        this.configuration = configuration;
        this.catalog = catalog;
        this.properties = properties;
    }

    @Override
    public RenderedTemplate render(TemplateCoordinates coordinates, Map<String, Object> variables) {
        String defaultLanguage = properties.getDefaultLocale();

        // The rich body is the part that must exist: a notification with no body is not a
        // notification, whereas a subject or a text alternative can legitimately be absent.
        TemplateSourceCatalog.TemplateSource body =
                resolve(coordinates, TemplateCoordinates.BODY_HTML, defaultLanguage)
                        .orElseThrow(() -> new TemplateNotFoundException(
                                coordinates.templateKey(),
                                coordinates.channel().name(),
                                localeTag(coordinates.locale())));

        String resolvedLocale = TemplateCoordinates.localeOf(body.name());
        Map<String, Object> model = model(variables, resolvedLocale);

        String htmlBody = evaluate(body, model);
        String subject = resolve(coordinates, TemplateCoordinates.SUBJECT, defaultLanguage)
                .map(source -> evaluate(source, model))
                .map(String::strip)
                .orElse(null);
        String textBody = resolve(coordinates, TemplateCoordinates.BODY_TEXT, defaultLanguage)
                .map(source -> evaluate(source, model))
                .orElse(null);

        if (coordinates.channel() == ChannelType.EMAIL && textBody == null) {
            // Not fatal, but worth saying once per template rather than never: a multipart email with
            // no text alternative is what a spam filter scores against, and the fix is one file.
            log.debug("Email template {} has no {} part; the message will be HTML-only",
                    coordinates.templateKey(), TemplateCoordinates.BODY_TEXT);
        }

        return new RenderedTemplate(body.name(), body.text(), resolvedLocale, body.contentHash(),
                subject, htmlBody, textBody);
    }

    /** Walks the candidate names most-specific-first and returns the first that exists. */
    private Optional<TemplateSourceCatalog.TemplateSource> resolve(TemplateCoordinates coordinates,
                                                                   String part,
                                                                   String defaultLanguage) {
        for (String candidate : coordinates.candidates(part, defaultLanguage)) {
            Optional<TemplateSourceCatalog.TemplateSource> source = catalog.load(candidate);
            if (source.isPresent()) {
                return source;
            }
        }
        return Optional.empty();
    }

    private String evaluate(TemplateSourceCatalog.TemplateSource source, Map<String, Object> model) {
        try {
            Template template = new Template(source.name(), new StringReader(source.text()), configuration);
            StringWriter output = new StringWriter();
            template.process(model, output);
            return output.toString();
        } catch (InvalidReferenceException e) {
            // The one failure worth naming precisely, because it is the one a caller can fix: the
            // blamed expression is the variable they did not send.
            throw new TemplateRenderException(source.name(),
                    "missing or null variable: " + e.getBlamedExpressionString(), e);
        } catch (TemplateException e) {
            throw new TemplateRenderException(source.name(), e.getMessageWithoutStackTop(), e);
        } catch (IOException e) {
            // A StringReader/StringWriter pair cannot fail on I/O, so reaching here means the
            // template engine reported something structural; treat it as a render failure rather
            // than letting an IOException escape a method that has no I/O in its contract.
            throw new TemplateRenderException(source.name(), e.getMessage(), e);
        }
    }

    /**
     * The data model, with the resolved locale exposed to the template.
     *
     * <p>A template needs to know which language it ended up as - a date format, a currency symbol, a
     * right-to-left wrapper - and deriving it from the variables is not possible, because the
     * fallback may have chosen a different language from the one requested.
     */
    private Map<String, Object> model(Map<String, Object> variables, String resolvedLocale) {
        Map<String, Object> model = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        model.put("locale", resolvedLocale);
        return model;
    }

    private static String localeTag(Locale locale) {
        return locale == null ? "" : locale.toLanguageTag();
    }
}
