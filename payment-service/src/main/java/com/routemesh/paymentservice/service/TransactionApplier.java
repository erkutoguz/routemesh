package com.routemesh.paymentservice.service;

import org.springframework.stereotype.Component;

import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.domain.PaymentStatus;
import com.routemesh.paymentservice.domain.PaymentTransaction;
import com.routemesh.paymentservice.domain.TxnStatus;
import com.routemesh.paymentservice.repository.PaymentRepository;

import io.micrometer.core.instrument.MeterRegistry;

@Component
public class TransactionApplier {

    private final PaymentRepository repository;
    private final PaymentEvents events;
    private final MeterRegistry meters;

    public TransactionApplier(PaymentRepository repository, PaymentEvents events, MeterRegistry meters) {
        this.repository = repository;
        this.events = events;
        this.meters = meters;
    }

    public boolean applySuccess(PaymentIntent locked, PaymentTransaction transaction, String pspReference) {
        PaymentTransaction current = repository.findTransactionById(transaction.id()).orElseThrow();
        if (current.status() == TxnStatus.SUCCEEDED) {
            return false;
        }
        repository.finishTransaction(transaction.id(), TxnStatus.SUCCEEDED, pspReference, null);

        switch (transaction.type()) {
            case CAPTURE -> {
                if (locked.status() == PaymentStatus.AUTHORIZED) {
                    move(locked, PaymentStatus.CAPTURED, transaction.amountMinor(), 0, transaction.reason());
                }
            }
            case VOID -> {
                if (locked.status() == PaymentStatus.AUTHORIZED || locked.status() == PaymentStatus.EXPIRED) {
                    move(locked, PaymentStatus.VOIDED, locked.capturedMinor(), locked.refundedMinor(),
                            transaction.reason());
                }
            }
            case REFUND -> {
                long refunded = locked.refundedMinor() + transaction.amountMinor();
                PaymentStatus target = refunded == locked.capturedMinor()
                        ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
                move(locked, target, locked.capturedMinor(), refunded, transaction.reason());
            }
        }
        return true;
    }

    public void move(PaymentIntent locked, PaymentStatus target, long capturedMinor, long refundedMinor,
                     String reason) {
        repository.updateIntent(locked.id(), target, capturedMinor, refundedMinor, reason);
        PaymentIntent updated = repository.findIntentById(locked.id()).orElseThrow();
        events.statusChanged(updated, locked.status(), reason);
        // from/to PaymentStatus sabit kümesidir
        meters.counter("routemesh.payment.status.transitions", "from_status", locked.status().name(),
                "to_status", target.name()).increment();
    }
}
