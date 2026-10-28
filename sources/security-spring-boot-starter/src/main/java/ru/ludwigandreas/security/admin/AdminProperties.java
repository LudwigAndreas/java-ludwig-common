package ru.ludwigandreas.security.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The administrative surface's configuration: whether it exists, and which of this service's roles hold which
 * operation.
 *
 * <pre>
 * ludwig:
 *   admin:
 *     grants:
 *       outbox.read:    [NOTIFICATION_ADMIN, NOTIFICATION_SUPPORT]
 *       outbox.operate: [NOTIFICATION_ADMIN]
 * </pre>
 *
 * <p>Keyed by operation rather than by role, because the question a reviewer asks is "who can discard outbox
 * messages?", and keyed this way the answer is one line. In a {@code .properties} file the dotted key needs the
 * bracket form, {@code ludwig.admin.grants[outbox.read]=NOTIFICATION_ADMIN}.
 */
@ConfigurationProperties(prefix = "ludwig.admin")
public class AdminProperties {

    /** Whether the administrative surface exists at all. Off removes every endpoint under /admin/ and its guard. */
    private boolean enabled = true;

    /**
     * Operation id to the roles that hold it. No entry means nobody holds the operation; there is no default, no
     * wildcard, and no tier implies another.
     */
    private Map<String, List<String>> grants = new LinkedHashMap<>();

    /**
     * Whether a request authenticated by a personal access token may reach the administrative surface. Off by
     * default: an administrative credential that outlives a session is the credential an attacker most wants.
     * The PAT management operations refuse such a token whatever this says.
     */
    private boolean allowPersonalAccessTokens = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, List<String>> getGrants() {
        return grants;
    }

    public void setGrants(Map<String, List<String>> grants) {
        this.grants = grants;
    }

    public boolean isAllowPersonalAccessTokens() {
        return allowPersonalAccessTokens;
    }

    public void setAllowPersonalAccessTokens(boolean allowPersonalAccessTokens) {
        this.allowPersonalAccessTokens = allowPersonalAccessTokens;
    }
}
