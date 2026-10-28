package ru.ludwigandreas.security.admin;

import ru.ludwigandreas.audit.AuditOutcome;

/**
 * How an administrative operation that the caller was authorized to perform ended.
 */
public enum AdminOutcome {

    /** It did what was asked. */
    SUCCEEDED,

    /** The object's state did not permit it - answered {@code 409} - and nothing changed. */
    REFUSED_BY_STATE,

    /** It was attempted and failed. */
    FAILED;

    AuditOutcome toAuditOutcome() {
        return switch (this) {
            case SUCCEEDED -> AuditOutcome.success();
            case REFUSED_BY_STATE -> AuditOutcome.denied("refused-by-state");
            case FAILED -> AuditOutcome.failure("failed");
        };
    }
}
