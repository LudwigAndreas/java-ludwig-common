package ru.ludwigandreas.usersettings.consent;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;

/**
 * Entity to service model, generated at compile time.
 *
 * <p>Hand-writing this would be six assignments and a loop, which is exactly the argument for
 * generating it: the two that are not trivial ({@code consentId} from the entity's id,
 * {@code recordedAt} from its import timestamp) are the ones a hand-written mapper eventually gets
 * wrong after a field is added, and the generator fails the build instead - the module compiles with
 * {@code unmappedTargetPolicy=ERROR}, so a new field on {@link ConsentRecord} is a compile error
 * until somebody says where it comes from.
 */
@Mapper
public interface ConsentEntityMapper {

    /**
     * {@code recordedAt} is when this deployment learned of the decision, which in a projection is
     * later than when the person made it - the distinction the two timestamps exist to preserve.
     */
    @Mapping(target = "consentId", source = "id")
    @Mapping(target = "recordedAt", source = "importedAt")
    ConsentRecord toRecord(UserConsentEntity entity);

    List<ConsentRecord> toRecords(List<UserConsentEntity> entities);
}
