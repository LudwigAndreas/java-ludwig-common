package ru.ludwigandreas.archrules.fixture.bad.operations.catalog.jobs;

import com.google.protobuf.ProtocolMessageEnum;

/**
 * A richer lifecycle that happens to be generated from a {@code .proto}. Like
 * {@link PartnerJobState} it carries states the common core cannot express, so it is not a second
 * vocabulary and the rule must stay silent on it - prefix stripping must not turn every generated
 * enum into a violation.
 */
public enum PartnerStateProto implements ProtocolMessageEnum {

    PARTNER_STATE_UNSPECIFIED,
    PARTNER_STATE_AWAITING_HANDLE,
    PARTNER_STATE_RUNNING,
    PARTNER_STATE_COLLECTING,
    PARTNER_STATE_SUCCEEDED,
    PARTNER_STATE_FAILED,
    PARTNER_STATE_EXPIRED,
    UNRECOGNIZED;

    @Override
    public int getNumber() {
        return ordinal();
    }
}
