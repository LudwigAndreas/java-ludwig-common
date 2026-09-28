package ru.ludwigandreas.hotreload.config;

import freemarker.cache.FileTemplateLoader;
import freemarker.template.Configuration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;
import ru.ludwigandreas.hotreload.core.watch.FileResourceWatcher;
import ru.ludwigandreas.hotreload.template.FreemarkerTemplateDirectorySource;
import ru.ludwigandreas.hotreload.template.HotReloadableTemplateLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * FreeMarker template hot reload: only activates when {@code ludwig.hotreload.freemarker.template-directory}
 * is set (and FreeMarker is on the classpath). Provides a {@link Configuration} bean only if the
 * consuming application doesn't already have one - if it does, wire {@link HotReloadableTemplateLoader}
 * into it manually (see module README) and call {@code bindTo} yourself.
 */
@AutoConfiguration
@AutoConfigureAfter(HotReloadAutoConfiguration.class)
@ConditionalOnClass({Configuration.class, FileTemplateLoader.class})
@ConditionalOnProperty(prefix = "ludwig.hotreload.freemarker", name = "template-directory")
public class HotReloadFreemarkerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(HotReloadableTemplateLoader.class)
    public HotReloadableTemplateLoader hotReloadableTemplateLoader(HotReloadProperties properties) throws IOException {
        File directory = new File(properties.getFreemarker().getTemplateDirectory());
        return new HotReloadableTemplateLoader(new FileTemplateLoader(directory));
    }

    @Bean
    @ConditionalOnMissingBean(Configuration.class)
    public Configuration hotReloadFreemarkerConfiguration(HotReloadableTemplateLoader templateLoader) {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_32);
        configuration.setDefaultEncoding("UTF-8");
        configuration.setTemplateLoader(templateLoader);
        templateLoader.bindTo(configuration);
        return configuration;
    }

    @Bean
    @ConditionalOnMissingBean(name = "hotReloadFreemarkerWatcherLifecycle")
    @ConditionalOnBean(HotReloadableTemplateLoader.class)
    public ResourceWatcherLifecycle hotReloadFreemarkerWatcherLifecycle(HotReloadProperties properties,
                                                                          HotReloadableTemplateLoader templateLoader,
                                                                          SourceReloadCoordinator coordinator) {
        Path directory = Path.of(properties.getFreemarker().getTemplateDirectory());
        FreemarkerTemplateDirectorySource source = new FreemarkerTemplateDirectorySource(directory);

        coordinator.addListener(event -> {
            if (event.sourceId().equals(source.id())) {
                templateLoader.invalidateAll();
            }
        });

        FileResourceWatcher watcher = new FileResourceWatcher(List.of(source), coordinator,
                properties.getFileWatch().getDebounce());
        return new ResourceWatcherLifecycle(watcher);
    }
}
