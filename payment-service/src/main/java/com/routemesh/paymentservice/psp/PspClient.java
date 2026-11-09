package com.routemesh.paymentservice.psp;

public interface PspClient {

    record CreatedPayment(String id, String checkoutUrl) {
    }

    CreatedPayment createPayment(String idempotencyKey, long amountMinor, String currency, String callbackUrl,
                                 String reference);

    String capture(String pspPaymentId, String idempotencyKey);

    String voidPayment(String pspPaymentId, String idempotencyKey);

    String refund(String pspPaymentId, String idempotencyKey, long amountMinor);
}
