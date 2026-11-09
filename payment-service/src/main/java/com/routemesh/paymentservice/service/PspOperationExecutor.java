package com.routemesh.paymentservice.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.domain.PaymentTransaction;
import com.routemesh.paymentservice.domain.TxnStatus;
import com.routemesh.paymentservice.psp.PspClient;
import com.routemesh.paymentservice.psp.PspRejectedException;
import com.routemesh.paymentservice.psp.PspUnavailableException;
import com.routemesh.paymentservice.repository.PaymentRepository;

@Component
public class PspOperationExecutor {

    public enum Outcome {
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    private static final Logger log = LoggerFactory.getLogger(PspOperationExecutor.class);

    private final PaymentRepository repository;
    private final PspClient psp;
    private final TransactionTemplate transactions;
    private final TransactionApplier applier;

    public PspOperationExecutor(PaymentRepository repository, PspClient psp, TransactionTemplate transactions,
                                TransactionApplier applier) {
        this.repository = repository;
        this.psp = psp;
        this.transactions = transactions;
        this.applier = applier;
    }

    public Outcome execute(UUID transactionId) {
        PaymentTransaction claimed = transactions.execute(status -> repository.claimTransaction(transactionId))
                .orElse(null);
        if (claimed == null) {
            return currentOutcome(transactionId);
        }
        PaymentIntent intent = repository.findIntentById(claimed.intentId()).orElseThrow();
        String idempotencyKey = claimed.id().toString();
        try {
            String reference = switch (claimed.type()) {
                case CAPTURE -> psp.capture(intent.pspPaymentId(), idempotencyKey);
                case VOID -> psp.voidPayment(intent.pspPaymentId(), idempotencyKey);
                case REFUND -> psp.refund(intent.pspPaymentId(), idempotencyKey, claimed.amountMinor());
            };
            transactions.executeWithoutResult(status -> {
                PaymentIntent locked = repository.findIntentByIdForUpdate(claimed.intentId()).orElseThrow();
                applier.applySuccess(locked, claimed, reference);
            });
            return Outcome.SUCCEEDED;
        } catch (PspRejectedException e) {
            log.warn("PSP rejected {} {} for payment {}: {}", claimed.type(), claimed.id(), claimed.intentId(),
                    e.getMessage());
            transactions.executeWithoutResult(status ->
                    repository.finishTransaction(claimed.id(), TxnStatus.FAILED, null, e.getMessage()));
            return Outcome.FAILED;
        } catch (PspUnavailableException e) {
            log.warn("PSP unavailable for {} {} (payment {}): {}", claimed.type(), claimed.id(),
                    claimed.intentId(), e.getMessage());
            transactions.executeWithoutResult(status ->
                    repository.finishTransaction(claimed.id(), TxnStatus.UNKNOWN, null, e.getMessage()));
            return Outcome.UNKNOWN;
        }
    }

    private Outcome currentOutcome(UUID transactionId) {
        PaymentTransaction transaction = repository.findTransactionById(transactionId).orElseThrow();
        return switch (transaction.status()) {
            case SUCCEEDED -> Outcome.SUCCEEDED;
            case FAILED -> Outcome.FAILED;
            default -> Outcome.UNKNOWN;
        };
    }
}
