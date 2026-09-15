package ru.ludwigandreas.identity.kafka;

import org.mapstruct.MapperConfig;
import org.mapstruct.ReportingPolicy;

/**
 * Shared MapStruct configuration for this module: Spring components, and an unmapped <em>source</em>
 * property is only a warning while an unmapped <em>target</em> stays an error.
 *
 * <p>The asymmetry is deliberate. A source field nobody consumes is normal - the event carries more than
 * the projection stores. A target field nobody fills is a bug, and on a security table it is the kind of
 * bug that produces a user with no roles or no tenant and looks like a permissions problem.
 */
@MapperConfig(
        componentModel = "spring",
        unmappedSourcePolicy = ReportingPolicy.WARN,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface IdentityMapperConfig {
}
