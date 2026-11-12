package com.routemesh.notificationservice.handler;

public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
