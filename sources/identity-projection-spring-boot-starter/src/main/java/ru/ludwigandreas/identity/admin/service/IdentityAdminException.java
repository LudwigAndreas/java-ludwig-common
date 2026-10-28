package ru.ludwigandreas.identity.admin.service;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Why an administrative request about partners, grants or subjects could not be carried out.
 */
public final class IdentityAdminException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private static final String PREFIX = "ludwig.identity.admin.";

    private IdentityAdminException(ProblemStatus status, String code, Object... args) {
        super(status, PREFIX + code, args);
    }

    static IdentityAdminException partnerNotFound(String code) {
        return new IdentityAdminException(ProblemStatus.NOT_FOUND, "partner-not-found", code);
    }

    static IdentityAdminException partnerCodeRequired() {
        return new IdentityAdminException(ProblemStatus.INVALID, "partner-code-required");
    }

    static IdentityAdminException partnerExists(String code) {
        return new IdentityAdminException(ProblemStatus.CONFLICT, "partner-exists", code);
    }

    static IdentityAdminException stalePartner(String code, long version) {
        return new IdentityAdminException(ProblemStatus.PRECONDITION_FAILED, "stale-partner", code, version);
    }

    static IdentityAdminException subjectNotFound(String subject) {
        return new IdentityAdminException(ProblemStatus.NOT_FOUND, "subject-not-found", subject);
    }

    static IdentityAdminException grantNotFound(UUID id) {
        return new IdentityAdminException(ProblemStatus.NOT_FOUND, "grant-not-found", id);
    }

    static IdentityAdminException dimensionNotAccepted(String resource, String dimension) {
        return new IdentityAdminException(ProblemStatus.INVALID, "dimension-not-accepted", resource, dimension);
    }

    static IdentityAdminException invalidGrant() {
        return new IdentityAdminException(ProblemStatus.INVALID, "invalid-grant");
    }
}
