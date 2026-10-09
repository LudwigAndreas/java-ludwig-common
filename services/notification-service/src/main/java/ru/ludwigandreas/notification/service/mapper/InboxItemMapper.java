package ru.ludwigandreas.notification.service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import ru.ludwigandreas.notification.repository.entity.DeliveryPriority;
import ru.ludwigandreas.notification.repository.entity.InboxItemContentEntity;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.notification.service.model.InboxItemView;
import ru.ludwigandreas.notification.service.model.Priority;

/**
 * Persistence model to service model for the inbox, generated at compile time by MapStruct.
 *
 * <p>Two mappings and not one, because the content split is load-bearing rather than incidental:
 * {@link #toView} is for a single item whose body the caller is about to display, and
 * {@link #toViewWithoutContent} is for a page and for the result of a transition, neither of which
 * has any use for a body. A single mapping taking a nullable content would compile and would make
 * the expensive case the default - a fifty-item page fetching fifty bodies to discard them.
 *
 * <p>The owner is deliberately absent from {@link InboxItemView}, so there is nothing to map: the
 * caller is the owner by construction, and a field on the response whose only use is to be sent back
 * is an invitation to accept it as a parameter.
 */
@Mapper
public interface InboxItemMapper {

    /** One item with its rendered body. {@code content} may be null once retention has removed it. */
    // The id is on both sources and is the same value - the content's primary key IS the item's,
    // which is what makes the one-to-one structural. Named explicitly because MapStruct is right to
    // refuse to guess between two equally plausible sources.
    @Mapping(target = "id", source = "item.id")
    @Mapping(target = "subject", source = "content.subject")
    @Mapping(target = "bodyHtml", source = "content.bodyHtml")
    @Mapping(target = "bodyText", source = "content.bodyText")
    @Mapping(target = "priority", source = "item.priority", qualifiedByName = "weightToPriority")
    @Mapping(target = "read", expression = "java(item.getReadAt() != null)")
    @Mapping(target = "dismissed", expression = "java(item.getDismissedAt() != null)")
    InboxItemView toView(InboxItemEntity item, InboxItemContentEntity content);

    /**
     * One item without its body, for a page and for a transition's response.
     *
     * <p>The three content fields are mapped to {@code null} explicitly rather than left unmapped,
     * because the build sets {@code unmappedTargetPolicy=ERROR} - which is what makes "this view
     * carries no content" a statement in the code rather than an omission a reader has to notice.
     */
    @Mapping(target = "subject", ignore = true)
    @Mapping(target = "bodyHtml", ignore = true)
    @Mapping(target = "bodyText", ignore = true)
    @Mapping(target = "priority", source = "priority", qualifiedByName = "weightToPriority")
    @Mapping(target = "read", expression = "java(item.getReadAt() != null)")
    @Mapping(target = "dismissed", expression = "java(item.getDismissedAt() != null)")
    InboxItemView toViewWithoutContent(InboxItemEntity item);

    /**
     * The stored priority weight back into the business enum.
     *
     * <p>The column is the weight rather than the name because the delivery's own priority column is,
     * and the two have to sort the same way for an operator comparing them. Null maps to
     * {@code NORMAL} rather than to null: a priority is not optional on the way out, and an item
     * written before the column existed should read as ordinary rather than as absent.
     */
    @Named("weightToPriority")
    default Priority weightToPriority(Short weight) {
        if (weight == null) {
            return Priority.NORMAL;
        }
        return Priority.valueOf(DeliveryPriority.ofWeight(weight).name());
    }
}
