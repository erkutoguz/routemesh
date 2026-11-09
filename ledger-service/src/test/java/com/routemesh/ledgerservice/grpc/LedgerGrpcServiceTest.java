package com.routemesh.ledgerservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.grpc.client.GrpcChannelFactory;

import com.routemesh.contracts.common.Money;
import com.routemesh.contracts.ledger.EntryDirection;
import com.routemesh.contracts.ledger.GetBalanceRequest;
import com.routemesh.contracts.ledger.LedgerEntry;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.contracts.ledger.PostTransactionRequest;
import com.routemesh.contracts.ledger.PostTransactionResponse;
import com.routemesh.ledgerservice.TestcontainersConfiguration;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestGrpcTransport
class LedgerGrpcServiceTest {

    private static final String PSP = "ASSET:PSP_CLEARING";
    private static final String ESCROW = "LIABILITY:ESCROW";

    @Autowired
    private GrpcChannelFactory channels;

    private ManagedChannel channel;
    private LedgerServiceGrpc.LedgerServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        channel = channels.createChannel("test");
        stub = LedgerServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
    }

    @Test
    void postTransaction_andGetBalance() {
        UUID shipment = UUID.randomUUID();

        PostTransactionResponse response = stub.postTransaction(capture(UUID.randomUUID().toString(), shipment, 1001));

        assertThat(response.getTransactionId()).isNotBlank();
        assertThat(response.getReplayed()).isFalse();
        assertThat(response.getCreatedAt().getSeconds()).isPositive();
        assertThat(stub.getBalance(GetBalanceRequest.newBuilder()
                .setAccountCode(ESCROW).setShipmentId(shipment.toString()).build())
                .getBalance().getAmountMinor()).isEqualTo(1001);
    }

    @Test
    void postTransaction_sameRequestTwice_isReplayed() {
        String key = UUID.randomUUID().toString();
        PostTransactionRequest request = capture(key, UUID.randomUUID(), 500);

        PostTransactionResponse first = stub.postTransaction(request);
        PostTransactionResponse second = stub.postTransaction(request);

        assertThat(second.getReplayed()).isTrue();
        assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());
    }

    @Test
    void postTransaction_unbalanced_isInvalidArgument() {
        PostTransactionRequest request = PostTransactionRequest.newBuilder()
                .setIdempotencyKey(UUID.randomUUID().toString())
                .setTransactionType("TEST_TRANSACTION")
                .setShipmentId(UUID.randomUUID().toString())
                .addEntries(entry(PSP, EntryDirection.ENTRY_DIRECTION_DEBIT, 500))
                .addEntries(entry(ESCROW, EntryDirection.ENTRY_DIRECTION_CREDIT, 400))
                .build();

        assertCode(() -> stub.postTransaction(request), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void postTransaction_unspecifiedDirection_isInvalidArgument() {
        PostTransactionRequest request = PostTransactionRequest.newBuilder()
                .setIdempotencyKey(UUID.randomUUID().toString())
                .setTransactionType("TEST_TRANSACTION")
                .setShipmentId(UUID.randomUUID().toString())
                .addEntries(entry(PSP, EntryDirection.ENTRY_DIRECTION_UNSPECIFIED, 500))
                .addEntries(entry(ESCROW, EntryDirection.ENTRY_DIRECTION_CREDIT, 500))
                .build();

        assertCode(() -> stub.postTransaction(request), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void postTransaction_invalidShipmentId_isInvalidArgument() {
        PostTransactionRequest request = capture(UUID.randomUUID().toString(), UUID.randomUUID(), 500).toBuilder()
                .setShipmentId("not-a-uuid").build();

        assertCode(() -> stub.postTransaction(request), Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void postTransaction_sameKeyDifferentPayload_isAlreadyExists() {
        String key = UUID.randomUUID().toString();
        UUID shipment = UUID.randomUUID();
        stub.postTransaction(capture(key, shipment, 500));

        assertCode(() -> stub.postTransaction(capture(key, shipment, 600)), Status.Code.ALREADY_EXISTS);
    }

    @Test
    void postTransaction_insufficientFunds_isFailedPrecondition() {
        UUID shipment = UUID.randomUUID();
        stub.postTransaction(capture(UUID.randomUUID().toString(), shipment, 100));
        PostTransactionRequest spend = PostTransactionRequest.newBuilder()
                .setIdempotencyKey(UUID.randomUUID().toString())
                .setTransactionType("TEST_TRANSACTION")
                .setShipmentId(shipment.toString())
                .addEntries(entry(ESCROW, EntryDirection.ENTRY_DIRECTION_DEBIT, 101))
                .addEntries(entry("REVENUE:PLATFORM_FEE", EntryDirection.ENTRY_DIRECTION_CREDIT, 101))
                .build();

        assertCode(() -> stub.postTransaction(spend), Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void getBalance_unknownAccount_isNotFound() {
        GetBalanceRequest request = GetBalanceRequest.newBuilder()
                .setAccountCode("LIABILITY:COURIER_WALLET:" + UUID.randomUUID()).build();

        assertCode(() -> stub.getBalance(request), Status.Code.NOT_FOUND);
    }

    @Test
    void getBalance_invalidShipmentId_isInvalidArgument() {
        GetBalanceRequest request = GetBalanceRequest.newBuilder()
                .setAccountCode(ESCROW).setShipmentId("x").build();

        assertCode(() -> stub.getBalance(request), Status.Code.INVALID_ARGUMENT);
    }

    private static PostTransactionRequest capture(String key, UUID shipment, long amount) {
        return PostTransactionRequest.newBuilder()
                .setIdempotencyKey(key)
                .setTransactionType("TEST_TRANSACTION")
                .setShipmentId(shipment.toString())
                .addEntries(entry(PSP, EntryDirection.ENTRY_DIRECTION_DEBIT, amount))
                .addEntries(entry(ESCROW, EntryDirection.ENTRY_DIRECTION_CREDIT, amount))
                .build();
    }

    private static LedgerEntry entry(String account, EntryDirection direction, long amount) {
        return LedgerEntry.newBuilder()
                .setAccountCode(account)
                .setDirection(direction)
                .setAmount(Money.newBuilder().setAmountMinor(amount).setCurrency("TRY"))
                .build();
    }

    private static void assertCode(Runnable call, Status.Code code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(code));
    }
}
