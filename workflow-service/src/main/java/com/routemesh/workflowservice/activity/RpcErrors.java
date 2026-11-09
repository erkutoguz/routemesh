package com.routemesh.workflowservice.activity;

import java.util.function.Supplier;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.failure.ApplicationFailure;

final class RpcErrors {

    static final String REJECTED = "REJECTED";

    private RpcErrors() {
    }

    static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException e) {
            throw translate(e);
        }
    }

    static boolean isNotFound(StatusRuntimeException e) {
        return e.getStatus().getCode() == Status.Code.NOT_FOUND;
    }

    static ApplicationFailure rejected(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, REJECTED);
    }

    static RuntimeException translate(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        return switch (code) {
            case INVALID_ARGUMENT, NOT_FOUND, FAILED_PRECONDITION, ALREADY_EXISTS, PERMISSION_DENIED,
                    OUT_OF_RANGE, UNIMPLEMENTED -> ApplicationFailure.newNonRetryableFailureWithCause(
                    code + ": " + e.getStatus().getDescription(), REJECTED, e);
            default -> e;
        };
    }
}
