package ru.ludwigandreas.grpc.client.properties;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code ludwig.grpc.client.*}: a {@code defaults} block and named clients that inherit from it.
 *
 * <p>Field-level checks are few on purpose: almost every mistake here is only a mistake in
 * combination - a target in defaults, a credential over plaintext, a breaker that cannot open - and
 * {@code GrpcClientConfigurationValidator} reports those, all at once.
 *
 * <p>Nothing here is read directly by the pipeline. Every client's effective settings come from
 * {@link GrpcClientPropertiesMerger}, which applies the built-in defaults, then {@code defaults},
 * then the client, by the rules stated on that class.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = GrpcClientProperties.PREFIX)
public class GrpcClientProperties {

    /** The property prefix. */
    public static final String PREFIX = "ludwig.grpc.client";

    /** Inherited by every client, field by field. {@code target} and {@code auth.relay-enabled} are refused here. */
    @Valid
    private ClientSettings defaults = new ClientSettings();

    /** The named clients. */
    private Map<String, @Valid ClientSettings> clients = new LinkedHashMap<>();
}
