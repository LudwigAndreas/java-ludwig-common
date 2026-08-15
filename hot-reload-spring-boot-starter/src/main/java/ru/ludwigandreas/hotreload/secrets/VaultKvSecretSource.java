package ru.ludwigandreas.hotreload.secrets;

import org.springframework.vault.core.VaultKeyValueOperations;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultOperations;
import org.springframework.vault.support.VaultResponse;
import ru.ludwigandreas.hotreload.core.HotReloadException;
import ru.ludwigandreas.hotreload.core.ReloadableSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Polls a single Vault KV (v1 or v2) secret and exposes its data as a flat key/value map. KV secrets
 * carry no lease, so there is no push/renewal notification to hook into - {@link
 * ru.ludwigandreas.hotreload.core.watch.PollingResourceWatcher} is what actually keeps this fresh, on
 * {@code ludwig.hotreload.vault.poll-interval}.
 */
public class VaultKvSecretSource implements ReloadableSource {

    private final VaultOperations vaultOperations;
    private final String mount;
    private final String path;
    private final KeyValueBackend backend;
    private final String keyPrefix;

    public VaultKvSecretSource(VaultOperations vaultOperations, String mount, String path,
                                KeyValueBackend backend, String keyPrefix) {
        this.vaultOperations = vaultOperations;
        this.mount = mount;
        this.path = path;
        this.backend = backend;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public String id() {
        return "vault:" + mount + "/" + path;
    }

    @Override
    public Map<String, Object> load() {
        VaultKeyValueOperations kv = vaultOperations.opsForKeyValue(mount, backend);
        VaultResponse response = kv.get(path);
        if (response == null || response.getData() == null) {
            throw new HotReloadException("Vault secret not found at " + mount + "/" + path
                    + " (or the authenticated token/role lacks read access)");
        }

        Map<String, Object> data = response.getData();
        if (keyPrefix.isEmpty()) {
            return data;
        }
        Map<String, Object> prefixed = new LinkedHashMap<>();
        data.forEach((key, value) -> prefixed.put(keyPrefix + key, value));
        return prefixed;
    }
}
