package com.routemesh.paymentservice.psp;

public class PspUnavailableException extends RuntimeException {

    public PspUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
