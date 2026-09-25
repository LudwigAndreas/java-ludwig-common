package ru.ludwigandreas.storage.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.exception.ObjectStoreException;
import ru.ludwigandreas.storage.fs.FilesystemObjectStore;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * Wires one {@link ObjectStore}, and lets a service replace any part of it.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so a service that needs a store this module
 * does not ship - an in-memory one for a test, a second bucket under a qualifier - defines its own
 * and nothing here fights it.
 *
 * <p>The S3 half is deliberately not here. It lives in {@code S3ObjectStoreAutoConfiguration},
 * because the AWS SDK is an optional dependency of this module and a {@code @Bean} method whose
 * signature names {@code S3Client} cannot be in a class Spring always introspects - reading the
 * methods of such a class on a context without the SDK throws before any {@code @ConditionalOnClass}
 * on the method is consulted. A class-level condition on a separate auto-configuration is evaluated
 * from the class file's metadata, without loading it, which is the only arrangement that actually
 * makes the dependency optional.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObjectStorageProperties.class)
@ConditionalOnProperty(prefix = "ludwig.storage", name = "enabled", matchIfMissing = true)
public class ObjectStorageAutoConfiguration {

    /**
     * Contributes this module's error text to the shared problem pipeline.
     *
     * <p>A bundle rather than a {@code @RestControllerAdvice}: web-core owns the one RFC 9457
     * pipeline, every module's failures are {@code LocalizedException}s it already renders, and a
     * second advice in the context would render some errors one way and some another depending on
     * which one Spring happened to order first.
     *
     * @return the bundle, at the lowest precedence, so an application's own wording wins
     */
    @Bean
    @ConditionalOnMissingBean(name = "storageProblemMessages")
    public ProblemMessageBundle storageProblemMessages() {
        return ProblemMessageBundle.of("i18n/ludwig-storage-messages");
    }

    /**
     * The filesystem store, which is the default.
     *
     * @param properties the module's configuration
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    @ConditionalOnProperty(prefix = "ludwig.storage", name = "type",
            havingValue = "filesystem", matchIfMissing = true)
    public ObjectStore filesystemObjectStore(ObjectStorageProperties properties) {
        String configured = properties.getFilesystem().getRoot();
        if (configured != null && !configured.isBlank()) {
            // A configured root must already exist. Creating it would make a typo in a path
            // indistinguishable from a correct one, and the data would be in the wrong place with
            // nothing to say so.
            return new FilesystemObjectStore(Paths.get(configured));
        }
        Path fallback = Paths.get(System.getProperty("java.io.tmpdir"), "ludwig-object-storage");
        try {
            // The unconfigured default is created, because refusing to start over a directory
            // nobody was told the name of is a poor first experience of the module - and because
            // this path is by construction a temp directory rather than somewhere data belongs.
            Files.createDirectories(fallback);
        } catch (IOException e) {
            throw new ObjectStoreException("open", fallback.toString(), e);
        }
        log.info("No ludwig.storage.filesystem.root configured; objects will be stored under {}", fallback);
        return new FilesystemObjectStore(fallback);
    }
}
