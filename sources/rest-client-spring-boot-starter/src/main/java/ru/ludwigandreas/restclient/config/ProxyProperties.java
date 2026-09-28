package ru.ludwigandreas.restclient.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Forward proxy for one named client.
 *
 * <p>Per client rather than per JVM on purpose. {@code -Dhttps.proxyHost} is global, which means one
 * dependency that must go through an egress proxy drags every other outbound call - including the
 * OIDC issuer, the collector and the metrics push - through it too.
 */
@Getter
@Setter
public class ProxyProperties {

    /** Proxy host. Absent disables proxying for this client regardless of the other fields. */
    private String host;

    /** Proxy port. */
    @Positive
    @Max(65535)
    private Integer port;

    /**
     * Hosts reached directly. Each entry is matched literally or as a {@code *.suffix} wildcard.
     *
     * <p>Replaced rather than merged when a client declares its own - see
     * {@link ClientPropertiesMerger}.
     */
    private List<String> nonProxyHosts;

    /** Proxy username, for a proxy that demands authentication. */
    private String username;

    /** Proxy password. Redacted everywhere this configuration is printed. */
    private String password;
}
