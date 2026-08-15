package ru.ludwigandreas.hotreload.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.lease.SecretLeaseContainer;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;
import ru.ludwigandreas.hotreload.core.watch.PollingResourceWatcher;
import ru.ludwigandreas.hotreload.secrets.VaultClientFactory;
import ru.ludwigandreas.hotreload.secrets.VaultConnectionOptions;
import ru.ludwigandreas.hotreload.secrets.VaultKvSecretSource;
import ru.ludwigandreas.hotreload.secrets.VaultLeaseSecretBridge;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * HashiCorp Vault secret support: polling for static KV secrets, lease-driven push updates for dynamic
 * secrets (database/cloud credentials), Kubernetes auth by default.
 *
 * <p>Deliberately <em>not</em> a {@code BootstrapConfiguration}/pre-context-refresh mechanism the way
 * Spring Cloud Vault's legacy bootstrap context is: authenticating against Vault means a network call,
 * and doing that inside an {@code EnvironmentPostProcessor} - before logging, error handling or a
 * {@code TaskScheduler} exist - trades a small amount of "secrets are present from the very first bean"
 * convenience for a lot of startup fragility. Two ways to avoid that gap in practice: mount secrets via
 * the Vault Agent Injector as files and use {@code ludwig.hotreload.files} instead (Vault's own
 * recommended Kubernetes pattern, and available synchronously via {@link
 * HotReloadEnvironmentPostProcessor}), or consume Vault-sourced values through a lazily-resolved {@link
 * ru.ludwigandreas.hotreload.binding.RefreshableConfig} rather than a constructor-injected bean.
 */
@AutoConfiguration
@AutoConfigureAfter(HotReloadAutoConfiguration.class)
@ConditionalOnClass(VaultTemplate.class)
@ConditionalOnProperty(prefix = "ludwig.hotreload.vault", name = "enabled", havingValue = "true")
public class HotReloadVaultAutoConfiguration {

    @Bean(name = "hotReloadVaultTaskScheduler")
    @ConditionalOnMissingBean(name = "hotReloadVaultTaskScheduler")
    public TaskScheduler hotReloadVaultTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("hotreload-vault-");
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean(VaultOperations.class)
    public VaultTemplate hotReloadVaultTemplate(HotReloadProperties properties, TaskScheduler hotReloadVaultTaskScheduler) {
        HotReloadProperties.Vault vault = properties.getVault();
        VaultConnectionOptions options = new VaultConnectionOptions(
                URI.create(vault.getUri()),
                vault.getConnectTimeout(),
                vault.getReadTimeout(),
                vault.getKubernetes().getAuthPath(),
                vault.getKubernetes().getRole(),
                vault.getKubernetes().getServiceAccountTokenFile(),
                vault.getToken());
        return VaultClientFactory.create(options, hotReloadVaultTaskScheduler);
    }

    @Bean
    @ConditionalOnMissingBean(name = "hotReloadVaultKvWatcherLifecycle")
    public ResourceWatcherLifecycle hotReloadVaultKvWatcherLifecycle(HotReloadProperties properties,
                                                                       VaultOperations vaultOperations,
                                                                       SourceReloadCoordinator coordinator) {
        List<VaultKvSecretSource> sources = properties.getVault().getSecrets().stream()
                .filter(secret -> secret.getPath() != null && !secret.getPath().isBlank())
                .map(secret -> new VaultKvSecretSource(vaultOperations, secret.getMount(), secret.getPath(),
                        secret.getKvVersion() == 1 ? KeyValueBackend.KV_1 : KeyValueBackend.KV_2,
                        secret.getKeyPrefix()))
                .toList();
        PollingResourceWatcher watcher = new PollingResourceWatcher(sources, coordinator,
                properties.getVault().getPollInterval(), Duration.ZERO);
        return new ResourceWatcherLifecycle(watcher);
    }

    @Bean
    @ConditionalOnMissingBean(SecretLeaseContainer.class)
    public SecretLeaseContainer hotReloadSecretLeaseContainer(VaultOperations vaultOperations,
                                                                 TaskScheduler hotReloadVaultTaskScheduler,
                                                                 HotReloadProperties properties,
                                                                 SourceReloadCoordinator coordinator) {
        SecretLeaseContainer container = new SecretLeaseContainer(vaultOperations, hotReloadVaultTaskScheduler);
        VaultLeaseSecretBridge bridge = new VaultLeaseSecretBridge(container);
        for (HotReloadProperties.Vault.DynamicSecret dynamic : properties.getVault().getDynamicSecrets()) {
            if (dynamic.getPath() == null || dynamic.getPath().isBlank()) {
                continue;
            }
            bridge.register(dynamic.getPath(), dynamic.isRotating(), dynamic.getKeyPrefix(), coordinator);
        }
        return container;
    }
}
