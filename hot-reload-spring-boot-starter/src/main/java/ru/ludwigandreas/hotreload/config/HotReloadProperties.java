package ru.ludwigandreas.hotreload.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ludwig.hotreload")
public class HotReloadProperties {

    /** Master switch for the whole module's autoconfiguration. */
    private boolean enabled = true;

    /** Properties/YAML files to hot-reload into the {@code Environment}. */
    private List<FileEntry> files = new ArrayList<>();

    private final FileWatch fileWatch = new FileWatch();
    private final Vault vault = new Vault();
    private final Freemarker freemarker = new Freemarker();
    private final Audit audit = new Audit();
    private final Metrics metrics = new Metrics();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<FileEntry> getFiles() {
        return files;
    }

    public void setFiles(List<FileEntry> files) {
        this.files = files;
    }

    public FileWatch getFileWatch() {
        return fileWatch;
    }

    public Vault getVault() {
        return vault;
    }

    public Freemarker getFreemarker() {
        return freemarker;
    }

    public Audit getAudit() {
        return audit;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public static class FileEntry {

        /** Absolute (or working-directory-relative) path to a {@code .properties}, {@code .yml} or {@code .yaml} file. */
        private String path;

        /** Prepended as-is to every key loaded from this file, e.g. {@code "myapp."}; empty for none. */
        private String keyPrefix = "";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    public static class FileWatch {

        /** Quiet period after the last filesystem event in a watched directory before reloading. */
        private Duration debounce = Duration.ofMillis(500);

        public Duration getDebounce() {
            return debounce;
        }

        public void setDebounce(Duration debounce) {
            this.debounce = debounce;
        }
    }

    public static class Vault {

        private boolean enabled = false;

        /** e.g. {@code https://vault.vault.svc:8200} */
        private String uri;

        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(5);

        /** How often KV secrets (no lease of their own) are re-read. */
        private Duration pollInterval = Duration.ofSeconds(30);

        /** Static token auth - local development only; prefer {@link #kubernetes} in a cluster. */
        private String token;

        private final Kubernetes kubernetes = new Kubernetes();

        /** Static KV (v1 or v2) secrets, polled on {@link #pollInterval}. */
        private List<KvSecret> secrets = new ArrayList<>();

        /** Dynamic/leased secrets (database credentials, PKI, ...), pushed via Vault's lease renewal/rotation. */
        private List<DynamicSecret> dynamicSecrets = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public Kubernetes getKubernetes() {
            return kubernetes;
        }

        public List<KvSecret> getSecrets() {
            return secrets;
        }

        public void setSecrets(List<KvSecret> secrets) {
            this.secrets = secrets;
        }

        public List<DynamicSecret> getDynamicSecrets() {
            return dynamicSecrets;
        }

        public void setDynamicSecrets(List<DynamicSecret> dynamicSecrets) {
            this.dynamicSecrets = dynamicSecrets;
        }

        public static class Kubernetes {

            /** Mount path of Vault's Kubernetes auth method. */
            private String authPath = "kubernetes";

            /** Vault role bound to this workload's service account. */
            private String role;

            /** Defaults to the projected service account token path ({@code /var/run/secrets/kubernetes.io/serviceaccount/token}). */
            private String serviceAccountTokenFile;

            public String getAuthPath() {
                return authPath;
            }

            public void setAuthPath(String authPath) {
                this.authPath = authPath;
            }

            public String getRole() {
                return role;
            }

            public void setRole(String role) {
                this.role = role;
            }

            public String getServiceAccountTokenFile() {
                return serviceAccountTokenFile;
            }

            public void setServiceAccountTokenFile(String serviceAccountTokenFile) {
                this.serviceAccountTokenFile = serviceAccountTokenFile;
            }
        }

        public static class KvSecret {

            /** Name of the KV secrets engine mount, e.g. {@code "secret"}. */
            private String mount = "secret";

            /** Path of the secret within the mount, e.g. {@code "myapp/config"}. */
            private String path;

            private int kvVersion = 2;

            private String keyPrefix = "";

            public String getMount() {
                return mount;
            }

            public void setMount(String mount) {
                this.mount = mount;
            }

            public String getPath() {
                return path;
            }

            public void setPath(String path) {
                this.path = path;
            }

            public int getKvVersion() {
                return kvVersion;
            }

            public void setKvVersion(int kvVersion) {
                this.kvVersion = kvVersion;
            }

            public String getKeyPrefix() {
                return keyPrefix;
            }

            public void setKeyPrefix(String keyPrefix) {
                this.keyPrefix = keyPrefix;
            }
        }

        public static class DynamicSecret {

            /** Full read path of the dynamic secret, e.g. {@code "database/creds/my-role"}. */
            private String path;

            /** {@code true} for engines that replace credentials outright (rotating); {@code false} to just renew the lease. */
            private boolean rotating = false;

            private String keyPrefix = "";

            public String getPath() {
                return path;
            }

            public void setPath(String path) {
                this.path = path;
            }

            public boolean isRotating() {
                return rotating;
            }

            public void setRotating(boolean rotating) {
                this.rotating = rotating;
            }

            public String getKeyPrefix() {
                return keyPrefix;
            }

            public void setKeyPrefix(String keyPrefix) {
                this.keyPrefix = keyPrefix;
            }
        }
    }

    public static class Freemarker {

        private boolean enabled = true;

        /** Filesystem directory containing {@code .ftl} templates to hot-reload. Unset disables this feature. */
        private String templateDirectory;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTemplateDirectory() {
            return templateDirectory;
        }

        public void setTemplateDirectory(String templateDirectory) {
            this.templateDirectory = templateDirectory;
        }
    }

    public static class Audit {

        /** When false, no {@code HotReloadAuditLogger} listener is registered. */
        private boolean enabled = true;

        /**
         * Identifies <em>this application instance</em> in every audit entry - not the upstream author of
         * the change (see {@link ru.ludwigandreas.hotreload.audit.HotReloadAuditEntry}). Defaults to the
         * local hostname.
         */
        private String actor;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getActor() {
            return actor;
        }

        public void setActor(String actor) {
            this.actor = actor;
        }
    }

    public static class Metrics {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
