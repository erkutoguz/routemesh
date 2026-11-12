package com.routemesh.paymentservice.psp;

public class PspRejectedException extends RuntimeException {

    private final int httpStatus;

    public PspRejectedException(String message) {
        this(message, 0);
    }

    public PspRejectedException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    /** PSP'nin döndüğü HTTP durumu; bilinmiyorsa 0. */
    public int httpStatus() {
        return httpStatus;
    }
}
