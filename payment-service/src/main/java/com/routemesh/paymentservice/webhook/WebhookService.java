package com.routemesh.paymentservice.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.domain.PaymentStatus;
import com.routemesh.paymentservice.domain.PaymentTransaction;
import com.routemesh.paymentservice.domain.TxnType;
import com.routemesh.paymentservice.repository.PaymentRepository;
import com.routemesh.paymentservice.service.PspOperationExecutor;
import com.routemesh.paymentservice.service.TransactionApplier;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

@Service
public class WebhookService {

    public enum Result {
        APPLIED,
        DUPLICATE,
        IGNORED,
        UNKNOWN_PAYMENT,
        LATE_VOID_SCHEDULED,
        LATE_REFUND_SCHEDULED,
        RETRY_LATER
    }

    private final PaymentRepository repository;
    private final TransactionApplier applier;
    private final PspOperationExecutor operationExecutor;
    private final TransactionTemplate transactions;
    private final Executor taskExecutor;
    private final MeterRegistry meters;

    public WebhookService(PaymentRepository repository, TransactionApplier applier,
                          PspOperationExecutor operationExecutor, TransactionTemplate transactions,
                          @Qualifier("pspTaskExecutor") Executor taskExecutor, MeterRegistry meters) {
        this.repository = repository;
        this.applier = applier;
        this.operationExecutor = operationExecutor;
        this.transactions = transactions;
        this.taskExecutor = taskExecutor;
        this.meters = meters;
    }

    // outcome: Result adları ya da MALFORMED; olay türü dışarıdan geldiği için etiket yapılmaz
    private void count(String outcome) {
        meters.counter("routemesh.payment.webhooks", "outcome", outcome).increment();
    }

    public Result process(JsonNode event, String rawBody) {
        String eventId = event.path("id").asString("");
        String type = event.path("type").asString("");
        JsonNode data = event.path("data");
        String pspPaymentId = data.path("payment_id").asString("");
        if (eventId.isBlank() || type.isBlank() || pspPaymentId.isBlank()) {
            count("MALFORMED");
            throw new MalformedWebhookException("id, type and data.payment_id are required");
        }
        Result outcome = handle(eventId, type, data, pspPaymentId, rawBody);
        count(outcome.name());
        return outcome;
    }

    private Result handle(String eventId, String type, JsonNode data, String pspPaymentId, String rawBody) {
        return transactions.execute(status -> {
            UUID rowId = repository.insertWebhookEvent(eventId, type, pspPaymentId, rawBody).orElse(null);
            if (rowId == null) {
                return Result.DUPLICATE;
            }
            PaymentIntent intent = repository.findIntentByPspIdForUpdate(pspPaymentId).orElse(null);
            Result result = intent == null ? Result.UNKNOWN_PAYMENT : apply(type, data, intent);
            if (result == Result.RETRY_LATER) {
                status.setRollbackOnly();
                return result;
            }
            repository.setWebhookOutcome(rowId, result.name());
            return result;
        });
    }

    private Result apply(String type, JsonNode data, PaymentIntent intent) {
        return switch (type) {
            case "payment.authorized" -> authorized(data, intent);
            case "payment.declined" -> declined(data, intent);
            case "payment.captured" -> captured(data, intent);
            case "payment.voided" -> resolve(intent, TxnType.VOID, -1);
            case "refund.succeeded" -> resolve(intent, TxnType.REFUND, data.path("refund_amount_minor").asLong(-1));
            default -> Result.IGNORED;
        };
    }

    private Result authorized(JsonNode data, PaymentIntent intent) {
        return switch (intent.status()) {
            case CREATED -> {
                boolean mismatch = data.path("amount_minor").asLong(-1) != intent.amountMinor()
                        || !intent.currency().equals(data.path("currency").asString(""));
                if (mismatch) {
                    applier.move(intent, PaymentStatus.FAILED, 0, 0, "AMOUNT_MISMATCH");
                    schedule(intent, TxnType.VOID, "amount-mismatch", intent.amountMinor(), "AMOUNT_MISMATCH");
                } else {
                    applier.move(intent, PaymentStatus.AUTHORIZED, 0, 0, null);
                }
                yield Result.APPLIED;
            }
            case EXPIRED -> {
                schedule(intent, TxnType.VOID, "late-authorization", intent.amountMinor(), "LATE_AUTHORIZATION");
                yield Result.LATE_VOID_SCHEDULED;
            }
            default -> Result.IGNORED;
        };
    }

    private Result declined(JsonNode data, PaymentIntent intent) {
        if (intent.status() != PaymentStatus.CREATED) {
            return Result.IGNORED;
        }
        applier.move(intent, PaymentStatus.FAILED, 0, 0, data.path("decline_reason").asString("DECLINED"));
        return Result.APPLIED;
    }

    private Result captured(JsonNode data, PaymentIntent intent) {
        return switch (intent.status()) {
            case AUTHORIZED -> resolve(intent, TxnType.CAPTURE, -1);
            case EXPIRED -> {
                long amount = data.path("amount_minor").asLong(intent.amountMinor());
                repository.updateIntent(intent.id(), PaymentStatus.EXPIRED, amount, 0, intent.failureReason());
                schedule(intent, TxnType.REFUND, "late-capture", amount, "LATE_PAYMENT");
                yield Result.LATE_REFUND_SCHEDULED;
            }
            case CREATED -> Result.RETRY_LATER;
            default -> Result.IGNORED;
        };
    }

    private Result resolve(PaymentIntent intent, TxnType type, long amountMinor) {
        List<PaymentTransaction> open = repository.findOpenTransactions(intent.id(), type);
        for (PaymentTransaction transaction : open) {
            if (amountMinor < 0 || transaction.amountMinor() == amountMinor) {
                applier.applySuccess(intent, transaction, intent.pspPaymentId());
                return Result.APPLIED;
            }
        }
        return Result.IGNORED;
    }

    private void schedule(PaymentIntent intent, TxnType type, String commandKey, long amountMinor, String reason) {
        repository.insertTransaction(intent.id(), type, commandKey, hash(type + "|" + commandKey), amountMinor,
                reason).ifPresent(transaction -> afterCommit(() -> taskExecutor.execute(
                () -> operationExecutor.execute(transaction.id()))));
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
