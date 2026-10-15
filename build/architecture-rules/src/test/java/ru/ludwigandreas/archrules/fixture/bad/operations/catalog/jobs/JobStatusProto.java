package ru.ludwigandreas.archrules.fixture.bad.operations.catalog.jobs;

import com.google.protobuf.ProtocolMessageEnum;

/**
 * The same sixth vocabulary as {@link ExportJobStatus}, declared in a {@code .proto} instead.
 *
 * <p>Protobuf style hides it well: every value carries the enum's name as a prefix, there is a
 * mandatory zero value and protoc adds {@code UNRECOGNIZED}, so not one constant here is literally
 * a shared word. A contract module that declares this instead of importing the platform's operation
 * message has restated the vocabulary across a service boundary, which is the expensive place to
 * do it.
 */
public enum JobStatusProto implements ProtocolMessageEnum {

    JOB_STATUS_UNSPECIFIED,
    JOB_STATUS_PENDING,
    JOB_STATUS_RUNNING,
    JOB_STATUS_SUCCEEDED,
    JOB_STATUS_FAILED,
    JOB_STATUS_CANCELLED,
    UNRECOGNIZED;

    @Override
    public int getNumber() {
        return ordinal();
    }
}
