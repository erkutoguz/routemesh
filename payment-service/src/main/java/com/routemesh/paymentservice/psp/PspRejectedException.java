package com.routemesh.paymentservice.psp;

public class PspRejectedException extends RuntimeException {

    public PspRejectedException(String message) {
        super(message);
    }
}
