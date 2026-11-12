package com.routemesh.paymentservice.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.routemesh.contracts.ledger.GetClearingBalancesRequest;
import com.routemesh.contracts.ledger.GetClearingBalancesResponse;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.contracts.ledger.ShipmentClearingBalance;
import com.routemesh.paymentservice.reconciliation.ReconciliationProperties;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

class GrpcLedgerClearingClientTest {

    private final LedgerServiceGrpc.LedgerServiceBlockingStub stub = mock(LedgerServiceGrpc.LedgerServiceBlockingStub.class);
    private final GrpcLedgerClearingClient client = new GrpcLedgerClearingClient(stub, properties(500));

    @Test
    void aLargeSetIsSplitIntoBatchesOfAtMostTheConfiguredSize() {
        when(stub.getClearingBalances(any(GetClearingBalancesRequest.class))).thenAnswer(invocation ->
                respondWith(invocation.getArgument(0, GetClearingBalancesRequest.class), 7));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            ids.add(UUID.randomUUID());
        }

        Map<UUID, Long> result = client.clearingNetDebit(ids);

        ArgumentCaptor<GetClearingBalancesRequest> requests = ArgumentCaptor.forClass(GetClearingBalancesRequest.class);
        verify(stub, times(3)).getClearingBalances(requests.capture());
        assertThat(requests.getAllValues()).extracting(GetClearingBalancesRequest::getShipmentIdsCount)
                .containsExactly(500, 500, 200);
        assertThat(result).hasSize(1200).containsKeys(ids.getFirst(), ids.getLast());
        assertThat(result.values()).containsOnly(7L);
    }

    @Test
    void duplicatesAreAskedOnceAndAnEmptySetMakesNoCall() {
        UUID id = UUID.randomUUID();
        when(stub.getClearingBalances(any(GetClearingBalancesRequest.class))).thenAnswer(invocation ->
                respondWith(invocation.getArgument(0, GetClearingBalancesRequest.class), -5));

        assertThat(client.clearingNetDebit(List.of(id, id, id))).containsExactly(Map.entry(id, -5L));
        ArgumentCaptor<GetClearingBalancesRequest> request = ArgumentCaptor.forClass(GetClearingBalancesRequest.class);
        verify(stub).getClearingBalances(request.capture());
        assertThat(request.getValue().getShipmentIdsList()).containsExactly(id.toString());

        assertThat(client.clearingNetDebit(List.of())).isEmpty();
        verify(stub, times(1)).getClearingBalances(any());
    }

    @Test
    void aResponseThatLeavesOutAShipmentIsABadResponseNotAZero() {
        UUID asked = UUID.randomUUID();
        UUID forgotten = UUID.randomUUID();
        when(stub.getClearingBalances(any(GetClearingBalancesRequest.class))).thenReturn(
                GetClearingBalancesResponse.newBuilder().addBalances(ShipmentClearingBalance.newBuilder()
                        .setShipmentId(asked.toString()).setNetDebitMinor(10)).build());

        assertThatThrownBy(() -> client.clearingNetDebit(List.of(asked, forgotten)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(forgotten.toString());
    }

    @Test
    void ledgerErrorsPropagateUntouchedSoTheCallerCanClassifyThem() {
        when(stub.getClearingBalances(any(GetClearingBalancesRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));

        assertThatThrownBy(() -> client.clearingNetDebit(List.of(UUID.randomUUID())))
                .isInstanceOf(StatusRuntimeException.class)
                .satisfies(e -> assertThat(((StatusRuntimeException) e).getStatus().getCode())
                        .isEqualTo(Status.Code.UNAVAILABLE));
        verify(stub, never()).getBalance(any());
    }

    private static GetClearingBalancesResponse respondWith(GetClearingBalancesRequest request, long net) {
        GetClearingBalancesResponse.Builder response = GetClearingBalancesResponse.newBuilder();
        request.getShipmentIdsList().forEach(id -> response.addBalances(ShipmentClearingBalance.newBuilder()
                .setShipmentId(id).setNetDebitMinor(net)));
        return response.build();
    }

    private static ReconciliationProperties properties(int batch) {
        return new ReconciliationProperties(false, Duration.ofMinutes(30), Duration.ofMinutes(2), Duration.ofHours(1),
                Duration.ofHours(24), Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(30), 200,
                1000, 50, 1000, Duration.ofDays(7), true, batch);
    }
}
