package ru.ludwigandreas.identity.repository;

import java.util.Optional;
import ru.ludwigandreas.identity.entity.SecurityPartnerEntity;

/**
 * Partner lookups, written as QueryDSL rather than as derived query methods.
 *
 * <p>The method names avoid Spring Data's vocabulary on purpose ({@code findBySpiffeId} would be parsed
 * from the name and would break at runtime, not at compile time, if the field were renamed). These are
 * implemented against generated Q-types, so a renamed column fails the build.
 */
public interface SecurityPartnerQueryRepository {

    /** By SPIFFE URI - the preferred, rotation-stable identifier. */
    Optional<SecurityPartnerEntity> lookupBySpiffeId(String spiffeId);

    /** By DNS subject-alternative name, case-insensitively. */
    Optional<SecurityPartnerEntity> lookupByDnsSan(String dnsSan);

    /** By exact subject distinguished name - the last resort, since a DN changes on reissue. */
    Optional<SecurityPartnerEntity> lookupBySubjectDn(String subjectDn);

    /** By business code, which is what a grant row and a {@code partner_id} column refer to. */
    Optional<SecurityPartnerEntity> lookupByCode(String code);
}
