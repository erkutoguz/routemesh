package com.routemesh.ledgerservice.grpc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.grpc.server.service.GrpcService;

import com.google.protobuf.Timestamp;
import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.ledger.EntryDirection;
import com.routemesh.contracts.ledger.GetBalanceRequest;
import com.routemesh.contracts.ledger.GetBalanceResponse;
import com.routemesh.contracts.ledger.LedgerEntry;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.contracts.ledger.PostTransactionRequest;
import com.routemesh.contracts.ledger.PostTransactionResponse;
import com.routemesh.ledgerservice.domain.Direction;
import com.routemesh.ledgerservice.domain.EntryCommand;
import com.routemesh.ledgerservice.domain.PostTransactionCommand;
import com.routemesh.ledgerservice.domain.PostedTransaction;
import com.routemesh.ledgerservice.service.LedgerException;
import com.routemesh.ledgerservice.service.LedgerException.Reason;
import com.routemesh.ledgerservice.service.LedgerService;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

@GrpcService
public class LedgerGrpcService extends LedgerServiceGrpc.LedgerServiceImplBase {

    private static final String CURRENCY = "TRY";

    private final LedgerService ledgerService;

    public LedgerGrpcService(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @Override
    public void postTransaction(PostTransactionRequest request,
                                StreamObserver<PostTransactionResponse> responseObserver) {
        try {
            PostedTransaction posted = ledgerService.post(toCommand(request));
            responseObserver.onNext(PostTransactionResponse.newBuilder()
                    .setTransactionId(posted.id().toString())
                    .setReplayed(posted.replayed())
                    .setCreatedAt(toTimestamp(posted.createdAt()))
                    .build());
            responseObserver.onCompleted();
        } catch (LedgerException e) {
            responseObserver.onError(toStatus(e));
        }
    }

    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<GetBalanceResponse> responseObserver) {
        try {
            UUID shipmentId = request.hasShipmentId() ? parseUuid(request.getShipmentId(), "shipment_id") : null;
            long balance = ledgerService.balance(request.getAccountCode(), shipmentId);
            responseObserver.onNext(GetBalanceResponse.newBuilder()
                    .setAccountCode(request.getAccountCode())
                    .setBalance(Money.newBuilder().setAmountMinor(balance).setCurrency(CURRENCY))
                    .build());
            responseObserver.onCompleted();
        } catch (LedgerException e) {
            responseObserver.onError(toStatus(e));
        }
    }

    private PostTransactionCommand toCommand(PostTransactionRequest request) {
        UUID shipmentId = request.getShipmentId().isBlank() ? null : parseUuid(request.getShipmentId(), "shipment_id");
        UUID reversalOf = request.hasReversalOf() ? parseUuid(request.getReversalOf(), "reversal_of") : null;
        List<EntryCommand> entries = request.getEntriesList().stream().map(this::toEntry).toList();
        return new PostTransactionCommand(request.getIdempotencyKey(), request.getTransactionType(),
                shipmentId, reversalOf, entries);
    }

    private EntryCommand toEntry(LedgerEntry entry) {
        return new EntryCommand(entry.getAccountCode(), toDirection(entry.getDirection()),
                entry.getAmount().getAmountMinor(), entry.getAmount().getCurrency());
    }

    private static Direction toDirection(EntryDirection direction) {
        return switch (direction) {
            case ENTRY_DIRECTION_DEBIT -> Direction.DEBIT;
            case ENTRY_DIRECTION_CREDIT -> Direction.CREDIT;
            default -> null;
        };
    }

    private static UUID parseUuid(String value, String field) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return uuid;
        } catch (IllegalArgumentException e) {
            throw new LedgerException(Reason.INVALID_REQUEST, field + " must be a valid lower-case UUID");
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static io.grpc.StatusRuntimeException toStatus(LedgerException e) {
        Status status = switch (e.getReason()) {
            case INVALID_REQUEST -> Status.INVALID_ARGUMENT;
            case ACCOUNT_NOT_FOUND -> Status.NOT_FOUND;
            case INSUFFICIENT_FUNDS, INVALID_REVERSAL -> Status.FAILED_PRECONDITION;
            case IDEMPOTENCY_CONFLICT -> Status.ALREADY_EXISTS;
        };
        return status.withDescription(e.getMessage()).asRuntimeException();
    }
}
