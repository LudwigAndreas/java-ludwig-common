package ru.ludwigandreas.operation.grpc.v1;

import com.google.protobuf.ProtocolMessageEnum;

/**
 * Test stub, under its real name, of the status enum protoc generates from grpc-core's
 * {@code operation.proto}. It is the single type the operation-vocabulary rule exempts by name, so
 * the test needs a class with exactly this name to show the exemption holds - and, beside it, a
 * second generated enum of the same shape to show the exemption is not a hole.
 */
public enum OperationStatus implements ProtocolMessageEnum {

    OPERATION_STATUS_UNSPECIFIED,
    OPERATION_STATUS_PENDING,
    OPERATION_STATUS_RUNNING,
    OPERATION_STATUS_SUCCEEDED,
    OPERATION_STATUS_FAILED,
    OPERATION_STATUS_CANCELLED,
    OPERATION_STATUS_EXPIRED,
    UNRECOGNIZED;

    @Override
    public int getNumber() {
        return ordinal();
    }
}
