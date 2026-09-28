package ru.ludwigandreas.notification.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The slice of configuration that can change while the service runs.
 *
 * <p>Kept separate from {@link NotificationProperties} rather than making the whole tree
 * hot-reloadable, and the separation is the design rather than an implementation detail. Most of
 * what this service is configured with cannot be changed safely at runtime - a lease timeout that
 * shortens mid-cycle, a batch size that changes between the claim and the dispatch, a template
 * directory that moves under an open loader. Exposing the whole tree to reload would invite exactly
 * those edits at exactly the moment somebody is under pressure.
 *
 * <p>What genuinely belongs here is what an operator needs during an incident: turn a channel off
 * because its provider is melting down, and turn a rate limit down because the provider asked us to.
 * Both are idempotent, both take effect on the next poll cycle, and neither can corrupt anything
 * in flight.
 *
 * <p>Every field is a nullable override. Absent means "whatever {@link NotificationProperties} says",
 * so a runtime file that has never been written changes nothing, and deleting an override restores
 * the deployed default rather than leaving a null behind.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ludwig.notification.runtime")
public class NotificationRuntimeProperties {

    /**
     * Overrides per channel, keyed by the channel's name.
     *
     * <p>A {@code String} key rather than the service layer's {@code ChannelType} enum, deliberately.
     * These properties are read by every layer, so the package they live in has to depend on nothing -
     * a map keyed by a service-layer type would make the configuration depend on the service and the
     * service depend on the configuration, which is a cycle and, more practically, the thing that
     * stops the properties from being safely readable everywhere. {@code ChannelRuntime} does the one
     * lookup by name.
     */
    @Valid
    private Map<String, ChannelOverride> channels = new LinkedHashMap<>();

    /** Per-channel overrides. Both fields are nullable by design - see the class comment. */
    @Getter
    @Setter
    public static class ChannelOverride {

        /** Turns a channel off without a redeploy. Queued deliveries wait rather than fail. */
        private Boolean enabled;

        /** Cluster-wide sends permitted per rate-limit window; 0 means unlimited. */
        @Min(0)
        private Integer maxPerWindow;
    }
}
