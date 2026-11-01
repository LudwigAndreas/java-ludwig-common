package ru.ludwigandreas.identity.projection;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.feed.IdentityChange;

/**
 * Generated merge of an upsert's profile into the projected row - no hand-written field copying, and a new column
 * nobody wired up fails the build ({@code unmappedTargetPolicy=ERROR}).
 *
 * <p>{@code IGNORE} for nulls, so a profile that omits a field leaves the stored value alone: sources send partial
 * profiles, and a null-overwriting merge would erase a tenant on an unrelated update. Roles, status and provenance
 * are the projection's to set, not the merge's.
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface IdentityChangeMapper {

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "displayName", source = "profile.displayName")
    @Mapping(target = "tenantId", source = "profile.tenantId")
    @Mapping(target = "email", source = "profile.contact.email")
    @Mapping(target = "emailVerified", source = "profile.contact.emailVerified")
    @Mapping(target = "alternateEmail", source = "profile.contact.alternateEmail")
    @Mapping(target = "phoneNumber", source = "profile.contact.phoneNumber")
    @Mapping(target = "phoneVerified", source = "profile.contact.phoneVerified")
    @Mapping(target = "chatHandle", source = "profile.contact.chatHandle")
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "roles", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "sourceSystem", ignore = true)
    @Mapping(target = "sourceVersion", ignore = true)
    @Mapping(target = "sourceTimestamp", ignore = true)
    @Mapping(target = "importedAt", ignore = true)
    @Mapping(target = "new", ignore = true)
    void merge(IdentityChange.Upsert upsert, @MappingTarget SecurityUserEntity entity);
}
