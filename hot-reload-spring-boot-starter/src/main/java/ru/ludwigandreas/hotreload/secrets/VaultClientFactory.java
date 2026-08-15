package ru.ludwigandreas.hotreload.secrets;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.authentication.KubernetesAuthentication;
import org.springframework.vault.authentication.KubernetesAuthenticationOptions;
import org.springframework.vault.authentication.KubernetesServiceAccountTokenFile;
import org.springframework.vault.authentication.LifecycleAwareSessionManager;
import org.springframework.vault.authentication.SessionManager;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.ClientHttpRequestFactoryFactory;
import org.springframework.vault.client.VaultClients;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.ClientOptions;
import org.springframework.vault.support.SslConfiguration;
import org.springframework.web.client.RestTemplate;
import ru.ludwigandreas.hotreload.core.HotReloadException;

/**
 * Builds an authenticated {@link VaultTemplate} from {@link VaultConnectionOptions}. Uses {@link
 * LifecycleAwareSessionManager} rather than a one-shot login so the Vault token backing every request is
 * renewed automatically in the background for as long as the application runs - required for Kubernetes
 * auth, where the initial token typically has a TTL measured in minutes to hours, not the process
 * lifetime.
 */
public final class VaultClientFactory {

    private VaultClientFactory() {
    }

    public static VaultTemplate create(VaultConnectionOptions options, TaskScheduler taskScheduler) {
        VaultEndpoint endpoint = VaultEndpoint.from(options.uri());
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryFactory.create(
                new ClientOptions(options.connectTimeout(), options.readTimeout()), SslConfiguration.unconfigured());

        ClientAuthentication clientAuthentication = clientAuthentication(options, endpoint, requestFactory);
        RestTemplate sessionRestTemplate = VaultClients.createRestTemplate(endpoint, requestFactory);
        SessionManager sessionManager = new LifecycleAwareSessionManager(clientAuthentication, taskScheduler, sessionRestTemplate);

        return new VaultTemplate(endpoint, requestFactory, sessionManager);
    }

    private static ClientAuthentication clientAuthentication(VaultConnectionOptions options, VaultEndpoint endpoint,
                                                               ClientHttpRequestFactory requestFactory) {
        if (options.usesKubernetesAuth()) {
            KubernetesAuthenticationOptions authOptions = KubernetesAuthenticationOptions.builder()
                    .path(options.kubernetesAuthPath())
                    .role(options.kubernetesRole())
                    .jwtSupplier(options.kubernetesServiceAccountTokenFile() == null
                            ? new KubernetesServiceAccountTokenFile()
                            : new KubernetesServiceAccountTokenFile(options.kubernetesServiceAccountTokenFile()))
                    .build();
            RestTemplate authRestTemplate = VaultClients.createRestTemplate(endpoint, requestFactory);
            return new KubernetesAuthentication(authOptions, authRestTemplate);
        }
        if (options.usesStaticToken()) {
            return new TokenAuthentication(options.staticToken());
        }
        throw new HotReloadException("No Vault authentication configured: set either "
                + "ludwig.hotreload.vault.kubernetes.role (Kubernetes auth) or ludwig.hotreload.vault.token "
                + "(static token, dev use)");
    }
}
