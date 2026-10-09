package ru.ludwigandreas.notification.web.mapper;

import org.mapstruct.Mapper;
import ru.ludwigandreas.notification.service.model.InboxItemView;
import ru.ludwigandreas.notification.service.model.InboxSummary;
import ru.ludwigandreas.notification.web.dto.InboxItemResponse;
import ru.ludwigandreas.notification.web.dto.InboxSummaryResponse;

/**
 * Service model to wire model for the inbox, generated at compile time by MapStruct.
 *
 * <p>A third model layer for what is currently a field-for-field copy, and it earns its place for the
 * same reason the delivery's does: the published API is allowed to outlive an internal rename, and
 * the enum twins ({@code CategoryClass}/{@code CategoryClassDto},
 * {@code Priority}/{@code PriorityDto}) are proved to line up at compile time rather than at
 * runtime. The build sets {@code unmappedTargetPolicy=ERROR}, so a field added to the view and
 * forgotten on the response fails the build instead of silently not reaching the client.
 *
 * <p>Neither type carries an owner, so there is nothing here to omit - which is the point. The
 * absence is in the models themselves rather than being something this mapper has to remember not to
 * copy.
 */
@Mapper
public interface InboxDtoMapper {

    InboxItemResponse toResponse(InboxItemView view);

    InboxSummaryResponse toResponse(InboxSummary summary);
}
