package com.routemesh.shipmentservice.service;

public class ShipmentException extends RuntimeException {

    public enum Reason {
        INVALID_REQUEST,
        NOT_FOUND,
        ILLEGAL_TRANSITION,
        IDEMPOTENCY_CONFLICT,
        /** Bildirilen teslimat konumu hedeften 50 m'den uzak. */
        POD_TOO_FAR,
        /** Alıcı OTP'si yok ya da yanlış. */
        OTP_INVALID,
        /** OTP 5 hatalı denemeden sonra kilitli ya da süresi dolmuş; yenisi üretilmeli. */
        OTP_LOCKED,
        PAYLOAD_TOO_LARGE,
        UNSUPPORTED_MEDIA,
        STORAGE_UNAVAILABLE
    }

    private final Reason reason;

    public ShipmentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
