package ru.ludwigandreas.hotreload.secrets;

import ru.ludwigandreas.hotreload.core.HotReloadException;
import ru.ludwigandreas.hotreload.core.ReloadableSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link ReloadableSource} for a lease-backed Vault secret (dynamic database/cloud credentials, or any
 * secret requested from a {@code SecretLeaseContainer} in renewable/rotating mode). Unlike {@link
 * VaultKvSecretSource} this is never polled: {@link VaultLeaseSecretBridge} pushes new content into it
 * whenever the lease container delivers or rotates the secret, and {@link #load()} simply returns
 * whatever was pushed last.
 */
public class VaultLeaseSecretSource implements ReloadableSource {

    private final String path;
    private final String keyPrefix;
    private final AtomicReference<Map<String, Object>> latest = new AtomicReference<>();

    public VaultLeaseSecretSource(String path, String keyPrefix) {
        this.path = path;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public String id() {
        return "vault-lease:" + path;
    }

    @Override
    public Map<String, Object> load() {
        Map<String, Object> value = latest.get();
        if (value == null) {
            throw new HotReloadException("Vault lease secret '" + path + "' has not been delivered by the "
                    + "lease container yet");
        }
        return value;
    }

    void update(Map<String, Object> secrets) {
        if (keyPrefix.isEmpty()) {
            latest.set(Map.copyOf(secrets));
            return;
        }
        Map<String, Object> prefixed = new LinkedHashMap<>();
        secrets.forEach((key, value) -> prefixed.put(keyPrefix + key, value));
        latest.set(Map.copyOf(prefixed));
    }
}
