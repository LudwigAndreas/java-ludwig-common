package ru.ludwigandreas.hotreload.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.vault.core.lease.SecretLeaseContainer;
import org.springframework.vault.core.lease.domain.RequestedSecret;
import org.springframework.vault.core.lease.event.LeaseListenerAdapter;
import org.springframework.vault.core.lease.event.SecretLeaseCreatedEvent;
import org.springframework.vault.core.lease.event.SecretLeaseEvent;
import ru.ludwigandreas.hotreload.core.ReloadOutcome;
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
    private final Map<String, RequestedSecret> requested = new ConcurrentHashMap<>();
    private final Map<String, AtomicReference<ReloadOutcome>> delivered = new ConcurrentHashMap<>();

    public VaultLeaseSecretBridge(SecretLeaseContainer leaseContainer) {
        this.leaseContainer = leaseContainer;
    }

    /** @param rotating rotating (dynamic credentials that get replaced, not just renewed) vs. renewable */
    public void register(String path, boolean rotating, String keyPrefix, SourceReloadCoordinator coordinator) {
        VaultLeaseSecretSource source = new VaultLeaseSecretSource(path, keyPrefix);
        RequestedSecret requestedSecret = rotating
                ? leaseContainer.requestRotatingSecret(path)
                : leaseContainer.requestRenewableSecret(path);
        requested.put(path, requestedSecret);
        AtomicReference<ReloadOutcome> outcome = delivered.computeIfAbsent(path, key -> new AtomicReference<>());

        leaseContainer.addLeaseListener(new LeaseListenerAdapter() {
            @Override
            public void onLeaseEvent(SecretLeaseEvent event) {
                if (event.getSource() != requestedSecret) {
                    return;
                }
                if (event instanceof SecretLeaseCreatedEvent created) {
                    source.update(created.getSecrets());
                    outcome.set(coordinator.reloadOutcome(source));
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

    /**
     * Requests a new secret for {@code path} now, as an early rotation would, and reports what its delivery did.
     * Spring Vault fetches a rotating secret on the calling thread, so the outcome is that delivery's.
     *
     * @param path a registered path
     * @return the delivery's outcome; empty when no new secret was delivered during the call
     * @throws IllegalArgumentException when the path is not registered
     */
    public Optional<ReloadOutcome> rotate(String path) {
        RequestedSecret secret = requested.get(path);
        if (secret == null) {
            throw new IllegalArgumentException("no Vault lease secret registered for '" + path + "'");
        }
        AtomicReference<ReloadOutcome> outcome = delivered.get(path);
        outcome.set(null);
        leaseContainer.rotate(secret);
        return Optional.ofNullable(outcome.get());
    }
}
