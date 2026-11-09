package com.routemesh.paymentservice.webhook;

public class MalformedWebhookException extends RuntimeException {

    public MalformedWebhookException(String message) {
        super(message);
    }
}
