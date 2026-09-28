package ru.ludwigandreas.identity.kafka;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;

/**
 * Generated mapping from the Kafka event to the projection entity - no hand-written field copying, and a
 * new field on either side that nobody wired up fails the build ({@code unmappedTargetPolicy=ERROR}).
 *
 * <p>The provenance columns are filled from the event rather than from the clock: {@code sourceTimestamp}
 * is when the change happened upstream, which is what {@code IdentityProjectionService} compares to
 * decide whether an event is stale. Using receipt time here would make out-of-order delivery invisible.
 */
@Mapper(config = IdentityMapperConfig.class)
public interface OidcUserEventMapper {

    @Mapping(target = "id", source = "subject")
    @Mapping(target = "sourceTimestamp", source = "occurredAt")
    @Mapping(target = "sourceVersion", source = "sourceVersion")
    @Mapping(target = "sourceSystem", constant = "oidc")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "importedAt", ignore = true)
    @Mapping(target = "new", ignore = true)
    SecurityUserEntity toEntity(OidcUserEvent event);

    /**
     * Updates the row in place. {@code IGNORE} for nulls, so an event that omits an optional field leaves
     * the stored value alone instead of blanking it - the provider sends partial payloads for some change
     * types, and a null-overwriting merge would erase a tenant assignment on an unrelated update.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "sourceTimestamp", source = "occurredAt")
    @Mapping(target = "sourceVersion", source = "sourceVersion")
    @Mapping(target = "sourceSystem", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "importedAt", ignore = true)
    @Mapping(target = "new", ignore = true)
    void update(OidcUserEvent event, @MappingTarget SecurityUserEntity entity);
}
