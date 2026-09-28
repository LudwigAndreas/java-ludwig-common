package ru.ludwigandreas.export.config;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.sink.S3ReportSink;
import ru.ludwigandreas.storage.api.ObjectStore;

/**
 * The object-storage sink, for an estate with more than one replica.
 *
 * <h2>Why this is a separate auto-configuration</h2>
 *
 * <p>{@code object-storage-spring-boot-starter} is an optional dependency of this module - a service
 * running a single instance against a mounted volume should not have to resolve an AWS SDK to produce
 * a spreadsheet - and a {@code @Bean} method taking an {@link ObjectStore} parameter cannot live in
 * {@link ExportAutoConfiguration} for that reason. Spring calls {@code getDeclaredMethods} on every
 * configuration class it processes, which throws on a missing parameter type before any
 * {@code @ConditionalOnClass} on the method is evaluated; only a <em>class-level</em> condition is
 * checked from the class file's metadata without loading it. The same split exists in the storage
 * module itself, for the same reason.
 *
 * <p>Ordered before {@link ExportAutoConfiguration} so that when {@code sink.type} is {@code s3} this
 * sink is already defined and the filesystem sink's {@code @ConditionalOnMissingBean} stands down.
 * Both are gated on the property as well, so the ordering is not the only thing keeping two sinks off
 * the context.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ObjectStore.class)
@AutoConfigureBefore(ExportAutoConfiguration.class)
@EnableConfigurationProperties(ExportProperties.class)
@ConditionalOnProperty(prefix = "ludwig.export", name = "enabled", matchIfMissing = true)
public class ExportObjectStorageAutoConfiguration {

    /**
     * The sink that puts finished reports in a bucket.
     *
     * @param properties the module's configuration
     * @param store      the platform's one object store, wired by the storage module
     * @return the sink
     */
    @Bean
    @ConditionalOnMissingBean(ReportSink.class)
    @ConditionalOnProperty(prefix = "ludwig.export.sink", name = "type", havingValue = "s3")
    public ReportSink s3ReportSink(ExportProperties properties, ObjectStore store) {
        return new S3ReportSink(store, properties.getSink().getBucket(),
                properties.getSink().getKeyPrefix());
    }
}
