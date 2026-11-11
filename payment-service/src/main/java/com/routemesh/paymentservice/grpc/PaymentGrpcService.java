package com.routemesh.paymentservice.grpc;

import java.util.function.Supplier;

import org.springframework.grpc.server.service.GrpcService;

import com.routemesh.contracts.payment.CapturePaymentRequest;
import com.routemesh.contracts.payment.CreatePaymentIntentRequest;
import com.routemesh.contracts.payment.ExpirePaymentRequest;
import com.routemesh.contracts.payment.FindPaymentByShipmentRequest;
import com.routemesh.contracts.payment.GetPaymentRequest;
import com.routemesh.contracts.payment.PaymentCommandResponse;
import com.routemesh.contracts.payment.PaymentIntent;
import com.routemesh.contracts.payment.PaymentServiceGrpc;
import com.routemesh.contracts.payment.RefundPaymentRequest;
import com.routemesh.contracts.payment.VoidPaymentRequest;
import com.routemesh.paymentservice.service.PaymentException;
import com.routemesh.paymentservice.service.PaymentService;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@GrpcService
public class PaymentGrpcService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private final PaymentService paymentService;

    public PaymentGrpcService(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Override
    public void createPaymentIntent(CreatePaymentIntentRequest request,
                                    StreamObserver<PaymentCommandResponse> observer) {
        respond(observer, () -> PaymentMapper.toProto(paymentService.create(PaymentMapper.toCommand(request))));
    }

    @Override
    public void capturePayment(CapturePaymentRequest request, StreamObserver<PaymentCommandResponse> observer) {
        respond(observer, () -> PaymentMapper.toProto(paymentService.capture(PaymentMapper.toCommand(request))));
    }

    @Override
    public void voidPayment(VoidPaymentRequest request, StreamObserver<PaymentCommandResponse> observer) {
        respond(observer, () -> PaymentMapper.toProto(paymentService.voidPayment(PaymentMapper.toCommand(request))));
    }

    @Override
    public void refundPayment(RefundPaymentRequest request, StreamObserver<PaymentCommandResponse> observer) {
        respond(observer, () -> PaymentMapper.toProto(paymentService.refund(PaymentMapper.toCommand(request))));
    }

    @Override
    public void expirePayment(ExpirePaymentRequest request, StreamObserver<PaymentCommandResponse> observer) {
        respond(observer, () -> PaymentMapper.toProto(
                paymentService.expire(PaymentMapper.parseUuid(request.getPaymentId(), "payment_id"))));
    }

    @Override
    public void getPayment(GetPaymentRequest request, StreamObserver<PaymentIntent> observer) {
        respond(observer, () -> PaymentMapper.toProto(
                paymentService.get(PaymentMapper.parseUuid(request.getPaymentId(), "payment_id"))));
    }

    @Override
    public void findPaymentByShipment(FindPaymentByShipmentRequest request, StreamObserver<PaymentIntent> observer) {
        respond(observer, () -> PaymentMapper.toProto(paymentService.findCapturedByShipment(
                PaymentMapper.parseUuid(request.getShipmentId(), "shipment_id"))));
    }

    private static <T> void respond(StreamObserver<T> observer, Supplier<T> action) {
        try {
            observer.onNext(action.get());
            observer.onCompleted();
        } catch (PaymentException e) {
            observer.onError(toStatus(e));
        }
    }

    private static StatusRuntimeException toStatus(PaymentException e) {
        Status status = switch (e.getReason()) {
            case INVALID_REQUEST -> Status.INVALID_ARGUMENT;
            case NOT_FOUND -> Status.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT -> Status.ALREADY_EXISTS;
            case ILLEGAL_STATE, PSP_REJECTED -> Status.FAILED_PRECONDITION;
            case PSP_UNAVAILABLE -> Status.UNAVAILABLE;
        };
        return status.withDescription(e.getMessage()).asRuntimeException();
    }
}
