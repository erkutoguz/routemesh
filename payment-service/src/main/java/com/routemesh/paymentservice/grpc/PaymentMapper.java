package com.routemesh.paymentservice.grpc;

import java.time.Instant;
import java.util.UUID;

import com.google.protobuf.Timestamp;
import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.payment.CapturePaymentRequest;
import com.routemesh.contracts.payment.CreatePaymentIntentRequest;
import com.routemesh.contracts.payment.RefundPaymentRequest;
import com.routemesh.contracts.payment.VoidPaymentRequest;
import com.routemesh.paymentservice.domain.CommandResult;
import com.routemesh.paymentservice.domain.CreatePaymentCommand;
import com.routemesh.paymentservice.domain.OperationCommand;
import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.service.PaymentException;
import com.routemesh.paymentservice.service.PaymentException.Reason;

final class PaymentMapper {

    private static final String STATUS_PREFIX = "PAYMENT_STATUS_";

    private PaymentMapper() {
    }

    static CreatePaymentCommand toCommand(CreatePaymentIntentRequest request) {
        if (!request.hasAmount()) {
            throw invalid("amount is required");
        }
        return new CreatePaymentCommand(request.getIdempotencyKey(),
                parseUuid(request.getShipmentId(), "shipment_id"),
                request.getAmount().getAmountMinor(), request.getAmount().getCurrency());
    }

    static OperationCommand toCommand(CapturePaymentRequest request) {
        return new OperationCommand(parseUuid(request.getPaymentId(), "payment_id"), request.getIdempotencyKey(),
                0, "TRY", null);
    }

    static OperationCommand toCommand(VoidPaymentRequest request) {
        return new OperationCommand(parseUuid(request.getPaymentId(), "payment_id"), request.getIdempotencyKey(),
                0, "TRY", request.getReason());
    }

    static OperationCommand toCommand(RefundPaymentRequest request) {
        if (!request.hasAmount()) {
            throw invalid("amount is required");
        }
        return new OperationCommand(parseUuid(request.getPaymentId(), "payment_id"), request.getIdempotencyKey(),
                request.getAmount().getAmountMinor(), request.getAmount().getCurrency(), request.getReason());
    }

    static UUID parseUuid(String value, String field) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return uuid;
        } catch (IllegalArgumentException e) {
            throw invalid(field + " must be a valid lower-case UUID");
        }
    }

    static com.routemesh.contracts.payment.PaymentCommandResponse toProto(CommandResult result) {
        return com.routemesh.contracts.payment.PaymentCommandResponse.newBuilder()
                .setPayment(toProto(result.payment()))
                .setReplayed(result.replayed())
                .setPending(result.pending())
                .build();
    }

    static com.routemesh.contracts.payment.PaymentIntent toProto(PaymentIntent intent) {
        com.routemesh.contracts.payment.PaymentIntent.Builder builder =
                com.routemesh.contracts.payment.PaymentIntent.newBuilder()
                        .setId(intent.id().toString())
                        .setShipmentId(intent.shipmentId().toString())
                        .setAmount(Money.newBuilder().setAmountMinor(intent.amountMinor())
                                .setCurrency(intent.currency()))
                        .setStatus(com.routemesh.contracts.payment.PaymentStatus
                                .valueOf(STATUS_PREFIX + intent.status().name()))
                        .setRefundedAmount(Money.newBuilder().setAmountMinor(intent.refundedMinor())
                                .setCurrency(intent.currency()))
                        .setCheckoutUrl(intent.checkoutUrl())
                        .setExpiresAt(timestamp(intent.expiresAt()))
                        .setCreatedAt(timestamp(intent.createdAt()))
                        .setUpdatedAt(timestamp(intent.updatedAt()))
                        .setVersion(intent.version());
        if (intent.failureReason() != null) {
            builder.setFailureReason(intent.failureReason());
        }
        return builder.build();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static PaymentException invalid(String message) {
        return new PaymentException(Reason.INVALID_REQUEST, message);
    }
}
