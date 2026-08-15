package ru.ludwigandreas.hotreload.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.vault.core.lease.SecretLeaseContainer;
import org.springframework.vault.core.lease.domain.RequestedSecret;
import org.springframework.vault.core.lease.event.LeaseListenerAdapter;
import org.springframework.vault.core.lease.event.SecretLeaseCreatedEvent;
import org.springframework.vault.core.lease.event.SecretLeaseEvent;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;

/**
 * Wires one {@link VaultLeaseSecretSource} to a Vault {@link SecretLeaseContainer}: requests the secret
 * in renewable or rotating mode, and forwards every delivery (initial fetch, lease renewal that returns
 * fresh data, or full rotation) into the source and then into {@link SourceReloadCoordinator#reload}, so
 * it flows through the exact same diff/notify/{@code ConfigurationRefreshedEvent} path as file and KV
 * sources.
 */
public class VaultLeaseSecretBridge {

    private static final Logger log = LoggerFactory.getLogger(VaultLeaseSecretBridge.class);

    private final SecretLeaseContainer leaseContainer;

    public VaultLeaseSecretBridge(SecretLeaseContainer leaseContainer) {
        this.leaseContainer = leaseContainer;
    }

    /** @param rotating rotating (dynamic credentials that get replaced, not just renewed) vs. renewable */
    public void register(String path, boolean rotating, String keyPrefix, SourceReloadCoordinator coordinator) {
        VaultLeaseSecretSource source = new VaultLeaseSecretSource(path, keyPrefix);
        RequestedSecret requestedSecret = rotating
                ? leaseContainer.requestRotatingSecret(path)
                : leaseContainer.requestRenewableSecret(path);

        leaseContainer.addLeaseListener(new LeaseListenerAdapter() {
            @Override
            public void onLeaseEvent(SecretLeaseEvent event) {
                if (event.getSource() != requestedSecret) {
                    return;
                }
                if (event instanceof SecretLeaseCreatedEvent created) {
                    source.update(created.getSecrets());
                    coordinator.reload(source);
                }
            }

            @Override
            public void onLeaseError(SecretLeaseEvent event, Exception exception) {
                if (event.getSource() != requestedSecret) {
                    return;
                }
                log.warn("Vault lease error for secret '{}'", path, exception);
                coordinator.notifyError(source.id(), exception);
            }
        });
    }
}
