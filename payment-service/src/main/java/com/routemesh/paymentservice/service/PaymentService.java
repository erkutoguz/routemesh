package com.routemesh.paymentservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.paymentservice.config.PaymentProperties;
import com.routemesh.paymentservice.domain.CommandResult;
import com.routemesh.paymentservice.domain.CreatePaymentCommand;
import com.routemesh.paymentservice.domain.OperationCommand;
import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.domain.PaymentStatus;
import com.routemesh.paymentservice.domain.PaymentTransaction;
import com.routemesh.paymentservice.domain.TxnType;
import com.routemesh.paymentservice.psp.PspClient;
import com.routemesh.paymentservice.psp.PspRejectedException;
import com.routemesh.paymentservice.psp.PspUnavailableException;
import com.routemesh.paymentservice.repository.PaymentRepository;
import com.routemesh.paymentservice.repository.PaymentRepository.StoredIntent;
import com.routemesh.paymentservice.service.PaymentException.Reason;
import com.routemesh.paymentservice.service.PspOperationExecutor.Outcome;

@Service
public class PaymentService {

    private static final String CURRENCY = "TRY";
    private static final int MAX_KEY_LENGTH = 255;
    private static final int MAX_REASON_LENGTH = 200;

    private record Prepared(PaymentTransaction transaction, boolean replayed) {
    }

    private final PaymentRepository repository;
    private final PspClient psp;
    private final PspOperationExecutor executor;
    private final TransactionApplier applier;
    private final TransactionTemplate transactions;
    private final PaymentProperties properties;

    public PaymentService(PaymentRepository repository, PspClient psp, PspOperationExecutor executor,
                          TransactionApplier applier, TransactionTemplate transactions,
                          PaymentProperties properties) {
        this.repository = repository;
        this.psp = psp;
        this.executor = executor;
        this.applier = applier;
        this.transactions = transactions;
        this.properties = properties;
    }

    public CommandResult create(CreatePaymentCommand command) {
        requireKey(command.idempotencyKey(), "idempotency_key");
        if (command.amountMinor() <= 0) {
            throw invalid("amount must be greater than 0");
        }
        if (!CURRENCY.equals(command.currency())) {
            throw invalid("currency must be " + CURRENCY);
        }
        String requestHash = sha256(command.shipmentId() + "|" + command.amountMinor() + "|" + command.currency());

        StoredIntent existing = repository.findByIdempotencyKey(command.idempotencyKey()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }

        UUID intentId = UUID.randomUUID();
        PspClient.CreatedPayment created;
        try {
            created = psp.createPayment("create:" + command.idempotencyKey(), command.amountMinor(),
                    command.currency(), properties.webhookUrl(), intentId.toString());
        } catch (PspRejectedException e) {
            throw new PaymentException(Reason.PSP_REJECTED, e.getMessage());
        } catch (PspUnavailableException e) {
            throw new PaymentException(Reason.PSP_UNAVAILABLE, e.getMessage());
        }

        try {
            return transactions.execute(status -> {
                PaymentIntent inserted = repository.insertIntent(intentId, command, requestHash, created.id(),
                        created.checkoutUrl(), properties.intentTtl().toSeconds()).orElse(null);
                if (inserted == null) {
                    return replayOrConflict(repository.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(),
                            requestHash);
                }
                return new CommandResult(inserted, false, false);
            });
        } catch (DuplicateKeyException e) {
            StoredIntent winner = repository.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> e);
            return replayOrConflict(winner, requestHash);
        }
    }

    public CommandResult capture(OperationCommand command) {
        requireKey(command.idempotencyKey(), "idempotency_key");
        Prepared prepared = transactions.execute(status -> {
            PaymentIntent locked = lock(command.paymentId());
            String hash = sha256("CAPTURE|" + command.paymentId());
            PaymentTransaction existing = repository.findTransaction(locked.id(), TxnType.CAPTURE,
                    command.idempotencyKey()).orElse(null);
            if (existing != null) {
                return reuse(existing, hash);
            }
            requireStatus(locked, PaymentStatus.AUTHORIZED);
            if (repository.hasLiveTransaction(locked.id(), TxnType.CAPTURE)) {
                throw new PaymentException(Reason.ILLEGAL_STATE, "a capture was already requested");
            }
            return new Prepared(repository.insertTransaction(locked.id(), TxnType.CAPTURE, command.idempotencyKey(),
                    hash, locked.amountMinor(), null).orElseThrow(), false);
        });
        return finish(prepared);
    }

    public CommandResult voidPayment(OperationCommand command) {
        requireKey(command.idempotencyKey(), "idempotency_key");
        requireReason(command.reason());
        Prepared prepared = transactions.execute(status -> {
            PaymentIntent locked = lock(command.paymentId());
            String hash = sha256("VOID|" + command.paymentId() + "|" + command.reason());
            PaymentTransaction existing = repository.findTransaction(locked.id(), TxnType.VOID,
                    command.idempotencyKey()).orElse(null);
            if (existing != null) {
                return reuse(existing, hash);
            }
            requireStatus(locked, PaymentStatus.AUTHORIZED);
            if (repository.hasLiveTransaction(locked.id(), TxnType.VOID)) {
                throw new PaymentException(Reason.ILLEGAL_STATE, "a void was already requested");
            }
            return new Prepared(repository.insertTransaction(locked.id(), TxnType.VOID, command.idempotencyKey(),
                    hash, locked.amountMinor(), blankToNull(command.reason())).orElseThrow(), false);
        });
        return finish(prepared);
    }

    public CommandResult refund(OperationCommand command) {
        requireKey(command.idempotencyKey(), "idempotency_key");
        requireReason(command.reason());
        if (command.amountMinor() <= 0) {
            throw invalid("amount must be greater than 0");
        }
        if (!CURRENCY.equals(command.currency())) {
            throw invalid("currency must be " + CURRENCY);
        }
        Prepared prepared = transactions.execute(status -> {
            PaymentIntent locked = lock(command.paymentId());
            String hash = sha256("REFUND|" + command.paymentId() + "|" + command.amountMinor() + "|"
                    + command.reason());
            PaymentTransaction existing = repository.findTransaction(locked.id(), TxnType.REFUND,
                    command.idempotencyKey()).orElse(null);
            if (existing != null) {
                return reuse(existing, hash);
            }
            if (locked.status() != PaymentStatus.CAPTURED && locked.status() != PaymentStatus.PARTIALLY_REFUNDED) {
                throw new PaymentException(Reason.ILLEGAL_STATE, "payment is " + locked.status());
            }
            if (repository.refundsCommitted(locked.id()) + command.amountMinor() > locked.capturedMinor()) {
                throw new PaymentException(Reason.ILLEGAL_STATE, "refund exceeds the captured amount");
            }
            return new Prepared(repository.insertTransaction(locked.id(), TxnType.REFUND, command.idempotencyKey(),
                    hash, command.amountMinor(), blankToNull(command.reason())).orElseThrow(), false);
        });
        return finish(prepared);
    }

    public CommandResult expire(UUID paymentId) {
        return transactions.execute(status -> {
            PaymentIntent locked = lock(paymentId);
            if (locked.status() == PaymentStatus.EXPIRED) {
                return new CommandResult(locked, true, false);
            }
            requireStatus(locked, PaymentStatus.CREATED);
            applier.move(locked, PaymentStatus.EXPIRED, 0, 0, "PAYMENT_WINDOW_ELAPSED");
            return new CommandResult(repository.findIntentById(paymentId).orElseThrow(), false, false);
        });
    }

    public PaymentIntent get(UUID paymentId) {
        return repository.findIntentById(paymentId).orElseThrow(() -> notFound(paymentId));
    }

    public PaymentIntent findCapturedByShipment(UUID shipmentId) {
        return repository.findCapturedIntentByShipment(shipmentId)
                .orElseThrow(() -> new PaymentException(Reason.NOT_FOUND,
                        "no captured payment for shipment " + shipmentId));
    }

    private CommandResult finish(Prepared prepared) {
        Outcome outcome = executor.execute(prepared.transaction().id());
        PaymentIntent intent = repository.findIntentById(prepared.transaction().intentId()).orElseThrow();
        if (outcome == Outcome.FAILED) {
            String error = repository.findTransactionById(prepared.transaction().id()).orElseThrow().lastError();
            throw new PaymentException(Reason.PSP_REJECTED, error == null ? "PSP rejected the operation" : error);
        }
        return new CommandResult(intent, prepared.replayed(), outcome == Outcome.UNKNOWN);
    }

    private Prepared reuse(PaymentTransaction existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new PaymentException(Reason.IDEMPOTENCY_CONFLICT,
                    "idempotency_key was already used with a different request");
        }
        return new Prepared(existing, true);
    }

    private PaymentIntent lock(UUID paymentId) {
        return repository.findIntentByIdForUpdate(paymentId).orElseThrow(() -> notFound(paymentId));
    }

    private static void requireStatus(PaymentIntent intent, PaymentStatus expected) {
        if (intent.status() != expected) {
            throw new PaymentException(Reason.ILLEGAL_STATE,
                    "payment is " + intent.status() + ", expected " + expected);
        }
    }

    private CommandResult replayOrConflict(StoredIntent existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new PaymentException(Reason.IDEMPOTENCY_CONFLICT,
                    "idempotency_key was already used with a different request");
        }
        return new CommandResult(existing.intent(), true, false);
    }

    private static void requireKey(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_KEY_LENGTH) {
            throw invalid(field + " must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
    }

    private static void requireReason(String reason) {
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw invalid("reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PaymentException notFound(UUID paymentId) {
        return new PaymentException(Reason.NOT_FOUND, "payment " + paymentId + " does not exist");
    }

    private static PaymentException invalid(String message) {
        return new PaymentException(Reason.INVALID_REQUEST, message);
    }
}
