package com.routemesh.paymentservice.domain;

public record CommandResult(PaymentIntent payment, boolean replayed, boolean pending) {
}
