package ru.ludwigandreas.hotreload.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import ru.ludwigandreas.hotreload.properties.PropertyFileSource;
import ru.ludwigandreas.hotreload.propertysource.HotReloadPropertySourceNames;
import ru.ludwigandreas.hotreload.propertysource.ReloadablePropertySource;

import java.nio.file.Path;

/**
 * Registers one empty-then-eagerly-populated {@link ReloadablePropertySource} per configured hot-reload
 * file/Vault secret into the {@code Environment} before the application context refreshes, at the
 * lowest precedence slot ({@link MutablePropertySources#addLast}) - below system properties, environment
 * variables and any {@code application.yml}/{@code .properties} value, so those can always override a
 * hot-reloaded one (the "environment overrides" guarantee), while still ranking above {@code
 * SpringApplication.setDefaultProperties}.
 *
 * <p>File sources are loaded synchronously right here, so their content is already bound-able by any
 * {@code @ConfigurationProperties} bean created during context refresh - not just once {@link
 * HotReloadAutoConfiguration}'s background watcher starts. Vault-backed sources only get their
 * precedence slot reserved here; {@code HotReloadVaultAutoConfiguration} populates them once the
 * context is far enough along to authenticate against Vault (see that class's Javadoc for why).
 */
public class HotReloadEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(HotReloadEnvironmentPostProcessor.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        HotReloadProperties properties = Binder.get(environment).bindOrCreate("ludwig.hotreload", HotReloadProperties.class);
        if (!properties.isEnabled()) {
            return;
        }

        MutablePropertySources propertySources = environment.getPropertySources();

        for (HotReloadProperties.FileEntry entry : properties.getFiles()) {
            if (entry.getPath() == null || entry.getPath().isBlank()) {
                continue;
            }
            PropertyFileSource fileSource = new PropertyFileSource(Path.of(entry.getPath()), entry.getKeyPrefix());
            ReloadablePropertySource propertySource = new ReloadablePropertySource(
                    HotReloadPropertySourceNames.forSourceId(fileSource.id()));
            try {
                propertySource.update(fileSource.load());
            } catch (Exception e) {
                log.warn("Initial load of hot-reloadable file '{}' failed; it will be retried once background "
                        + "watching starts", fileSource.path(), e);
            }
            propertySources.addLast(propertySource);
        }

        if (properties.getVault().isEnabled()) {
            for (HotReloadProperties.Vault.KvSecret secret : properties.getVault().getSecrets()) {
                reserveSlot(propertySources, "vault:" + secret.getMount() + "/" + secret.getPath());
            }
            for (HotReloadProperties.Vault.DynamicSecret dynamic : properties.getVault().getDynamicSecrets()) {
                reserveSlot(propertySources, "vault-lease:" + dynamic.getPath());
            }
        }
    }

    private static void reserveSlot(MutablePropertySources propertySources, String sourceId) {
        String name = HotReloadPropertySourceNames.forSourceId(sourceId);
        if (propertySources.contains(name)) {
            return;
        }
        propertySources.addLast(new ReloadablePropertySource(name));
    }
}
