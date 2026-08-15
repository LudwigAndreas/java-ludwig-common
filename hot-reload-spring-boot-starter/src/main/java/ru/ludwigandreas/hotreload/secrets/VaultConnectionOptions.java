package ru.ludwigandreas.hotreload.secrets;

import java.net.URI;
import java.time.Duration;

/**
 * Everything needed to open an authenticated connection to Vault. Exactly one authentication mechanism
 * must be configured: {@link #kubernetesRole()} for Vault's Kubernetes auth method (the expected path
 * when running inside a pod - the projected/mounted service account JWT is exchanged for a Vault token
 * scoped to {@code kubernetesRole}), or {@link #staticToken()} for local development / non-Kubernetes use.
 */
public record VaultConnectionOptions(URI uri,
                                      Duration connectTimeout,
                                      Duration readTimeout,
                                      String kubernetesAuthPath,
                                      String kubernetesRole,
                                      String kubernetesServiceAccountTokenFile,
                                      String staticToken) {

    public boolean usesKubernetesAuth() {
        return kubernetesRole != null && !kubernetesRole.isBlank();
    }

    public boolean usesStaticToken() {
        return staticToken != null && !staticToken.isBlank();
    }
}
