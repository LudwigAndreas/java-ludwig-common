package ru.ludwigandreas.notification.config;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.SimpleObjectWrapper;
import freemarker.template.TemplateExceptionHandler;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.hotreload.template.HotReloadableTemplateLoader;
import ru.ludwigandreas.notification.service.template.FreemarkerTemplateRenderer;
import ru.ludwigandreas.notification.service.template.TemplateRenderer;
import ru.ludwigandreas.notification.service.template.TemplateSourceCatalog;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * The FreeMarker configuration templates are rendered with, and the loader they come from.
 *
 * <h2>Why this replaces Spring Boot's own FreeMarker autoconfiguration</h2>
 *
 * <p>Boot autoconfigures a {@code freemarker.template.Configuration} whenever FreeMarker is on the
 * classpath, aimed at rendering MVC <em>views</em>. This service renders no views - it renders
 * messages - and Boot's instance is configured for a different job: a different template root, and
 * none of the strictness settings below. Two beans of the same type is an ambiguous injection, and
 * resolving it by marking one {@code @Primary} would leave the wrong one reachable. So Boot's is
 * excluded by name in {@code application.yml} under {@code spring.autoconfigure.exclude} - Boot 3.x
 * has no {@code spring.freemarker.enabled} flag - and this is the only {@code Configuration} in the
 * context.
 *
 * <p>These beans are deliberately <em>not</em> {@code @ConditionalOnMissingBean}. This is an
 * application rather than a starter, and there is exactly one right answer for how its own templates
 * are rendered - a conditional here would only create a way for an unrelated dependency to take the
 * decision over silently.
 *
 * <h2>Two loaders, one decision</h2>
 *
 * <p>When {@code ludwig.notification.templates.directory} is set, the hot-reload module supplies a
 * {@link HotReloadableTemplateLoader} over that directory and watches it - so a wording change is a
 * ConfigMap update rather than a release, which is the whole point of putting templates in files.
 * When it is not set, templates load from the classpath: that is what a test and a local run want, and
 * a jar's contents cannot change, so nothing is lost.
 *
 * <h2>Why the settings below are what they are</h2>
 *
 * <p>Each one closes a specific hole, and three of them are the difference between a template engine
 * and a remote-code-execution surface.
 */
@Slf4j
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
public class NotificationTemplateConfig {

    /** FreeMarker's incompatible-improvements level; fixes a long list of old parser quirks. */
    private static final freemarker.template.Version FREEMARKER_VERSION = Configuration.VERSION_2_3_32;

    /**
     * The template loader, hot-reloadable when a directory is configured.
     *
     * <p>{@link ObjectProvider} rather than a hard dependency, so this service still starts when the
     * hot-reload module's FreeMarker support is switched off - which is exactly the case in a test.
     */
    @Bean
    public TemplateLoader notificationTemplateLoader(
            ObjectProvider<HotReloadableTemplateLoader> hotReloadLoader,
            NotificationProperties properties) {
        HotReloadableTemplateLoader reloadable = hotReloadLoader.getIfAvailable();
        if (reloadable != null) {
            log.info("Templates load from {} and reload without a restart",
                    properties.getTemplates().getDirectory());
            return reloadable;
        }
        log.info("Templates load from the classpath under {}/",
                properties.getTemplates().getClasspathPrefix());
        return new ClassTemplateLoader(NotificationTemplateConfig.class.getClassLoader(),
                properties.getTemplates().getClasspathPrefix());
    }

    /**
     * The FreeMarker configuration.
     *
     * <p>{@code SimpleObjectWrapper} is the security-relevant one. FreeMarker's default wrapper
     * exposes arbitrary Java beans to the template, and template variables here arrive as JSON from
     * calling services - so a default wrapper would let a caller who could influence a template reach
     * whatever objects the model happened to contain. The simple wrapper handles maps, lists, strings,
     * numbers and booleans and refuses everything else, which is exactly the set JSON produces.
     *
     * <p>{@code localizedLookup} is off because this service addresses locales through its own
     * directory scheme ({@code <key>/<channel>/<locale>/}); FreeMarker's own mechanism appends
     * {@code _ru} to file names and would resolve templates by a rule nobody reading the directory
     * could see.
     *
     * <p>{@code RETHROW_HANDLER} and the absence of any default-value setting are what make a missing
     * variable a loud failure instead of a blank in a customer's email. See
     * {@link FreemarkerTemplateRenderer} for why that matters more than it sounds.
     */
    @Bean
    public Configuration freemarkerConfiguration(TemplateLoader templateLoader) {
        Configuration configuration = new Configuration(FREEMARKER_VERSION);
        configuration.setTemplateLoader(templateLoader);
        configuration.setObjectWrapper(new SimpleObjectWrapper(FREEMARKER_VERSION));
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLocalizedLookup(false);
        // The renderer turns every template failure into a localized problem, so letting FreeMarker
        // also log it would put the same failure in the log twice, once without any context.
        configuration.setLogTemplateExceptions(false);
        // An unchecked exception thrown from inside a template is wrapped rather than escaping raw,
        // so the renderer's catch sees a TemplateException like every other failure.
        configuration.setWrapUncheckedExceptions(true);
        // A null loop variable is a bug in the template, not something to silently skip.
        configuration.setFallbackOnNullLoopVariable(false);
        // ?api would let a template call arbitrary methods on a wrapped object. Off by default in
        // this FreeMarker version; set explicitly so an upgrade cannot quietly change it.
        configuration.setAPIBuiltinEnabled(false);

        if (templateLoader instanceof HotReloadableTemplateLoader reloadable) {
            // Required: without it invalidateAll() has no Configuration whose cache to clear, and a
            // changed template would wait for FreeMarker's own lazy update check - which under low
            // traffic can be a very long time.
            reloadable.bindTo(configuration);
        }
        return configuration;
    }

    @Bean
    public TemplateSourceCatalog templateSourceCatalog(TemplateLoader templateLoader) {
        return new TemplateSourceCatalog(templateLoader);
    }

    @Bean
    public TemplateRenderer templateRenderer(Configuration configuration,
                                             TemplateSourceCatalog catalog,
                                             NotificationProperties properties) {
        return new FreemarkerTemplateRenderer(configuration, catalog, properties.getTemplates());
    }
}
