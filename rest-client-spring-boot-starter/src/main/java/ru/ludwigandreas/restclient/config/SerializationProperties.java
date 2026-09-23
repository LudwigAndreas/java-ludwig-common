package ru.ludwigandreas.restclient.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-client Jackson behaviour.
 *
 * <p>Unset, a client uses the application's own {@code ObjectMapper}, which is what you want for a
 * partner that shares the platform's conventions. A mapper is only forked when one of these is set,
 * because forking it means a service's global Jackson customizations - modules, a
 * {@code JavaTimeModule} configuration, a mixin - stop applying to that client, and doing that
 * silently to every client would be a large, invisible behaviour change.
 */
@Getter
@Setter
public class SerializationProperties {

    /**
     * Jackson naming strategy: {@code SNAKE_CASE}, {@code LOWER_CAMEL_CASE}, {@code KEBAB_CASE},
     * {@code UPPER_CAMEL_CASE}, {@code LOWER_CASE}, {@code LOWER_DOT_CASE}.
     */
    private String propertyNamingStrategy;

    /** {@code java.time} format for dates written and read by this client, e.g. {@code yyyy-MM-dd}. */
    private String dateFormat;

    /** Time zone applied to the format above, e.g. {@code UTC}. */
    private String timeZone;

    /**
     * Fail when the peer sends a field this service does not know. Built-in default: false.
     *
     * <p>False is the right default for an HTTP client and the wrong one for a server: a partner
     * adding a field to its response must not break this service, which is the whole basis of
     * tolerant reading.
     */
    private Boolean failOnUnknownProperties;

    /** Omit null-valued fields from request bodies. Built-in default: true. */
    private Boolean excludeNulls;

    /** Additional Jackson {@code Module} class names registered on this client's mapper. */
    private List<String> modules;
}
