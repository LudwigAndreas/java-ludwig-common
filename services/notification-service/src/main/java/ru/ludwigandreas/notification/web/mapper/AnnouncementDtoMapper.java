package ru.ludwigandreas.notification.web.mapper;

import java.util.UUID;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import ru.ludwigandreas.notification.service.model.AnnouncementAdminView;
import ru.ludwigandreas.notification.service.model.AnnouncementView;
import ru.ludwigandreas.notification.web.dto.AnnouncementAdminResponse;
import ru.ludwigandreas.notification.web.dto.AnnouncementResponse;

/**
 * Service model to wire model for announcements, generated at compile time by MapStruct.
 *
 * <p>Two targets and not one, because the difference between them is who may see the audience. The
 * recipient's response carries none; the publisher's does. Expressed as two types rather than one
 * with a sometimes-populated field, because a nullable audience is one line away from being populated
 * on the recipient's endpoint by mistake - and that mistake would tell everybody the platform
 * announces to which roles it addresses.
 *
 * <p>The build sets {@code unmappedTargetPolicy=ERROR}, so a field added to a view and forgotten here
 * fails the build rather than silently not reaching the client; and the enum twins
 * ({@code CategoryClass}/{@code CategoryClassDto}, {@code AudienceType}/{@code AudienceTypeDto}) are
 * proved to line up at compile time.
 */
@Mapper
public interface AnnouncementDtoMapper {

    /** The recipient's view. Deliberately has no audience to map. */
    AnnouncementResponse toResponse(AnnouncementView view);

    /**
     * The publisher's view.
     *
     * <p>Mapped from a service model and not from the entity: a controller handling a JPA entity
     * fails the layering rules, and rightly - it ties the published API to a column, so a rename
     * becomes a breaking change.
     */
    @Mapping(target = "emailRunId", source = "emailRunId")
    AnnouncementAdminResponse toAdminResponse(AnnouncementAdminView announcement, UUID emailRunId);
}
