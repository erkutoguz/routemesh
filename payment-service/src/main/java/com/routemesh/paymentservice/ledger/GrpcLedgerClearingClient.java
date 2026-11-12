package com.routemesh.paymentservice.ledger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.routemesh.contracts.ledger.GetClearingBalancesRequest;
import com.routemesh.contracts.ledger.GetClearingBalancesResponse;
import com.routemesh.contracts.ledger.LedgerServiceGrpc;
import com.routemesh.contracts.ledger.ShipmentClearingBalance;
import com.routemesh.paymentservice.reconciliation.ReconciliationProperties;

@Component
public class GrpcLedgerClearingClient implements LedgerClearingClient {

    private final LedgerServiceGrpc.LedgerServiceBlockingStub ledger;
    private final int batchSize;

    public GrpcLedgerClearingClient(LedgerServiceGrpc.LedgerServiceBlockingStub ledger,
                                    ReconciliationProperties properties) {
        this.ledger = ledger;
        this.batchSize = properties.ledgerBatchSize();
    }

    @Override
    public Map<UUID, Long> clearingNetDebit(Collection<UUID> shipmentIds) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(shipmentIds));
        Map<UUID, Long> result = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += batchSize) {
            List<UUID> batch = ids.subList(from, Math.min(ids.size(), from + batchSize));
            GetClearingBalancesRequest.Builder request = GetClearingBalancesRequest.newBuilder();
            batch.forEach(id -> request.addShipmentIds(id.toString()));
            GetClearingBalancesResponse response = ledger.getClearingBalances(request.build());
            for (ShipmentClearingBalance balance : response.getBalancesList()) {
                result.put(UUID.fromString(balance.getShipmentId()), balance.getNetDebitMinor());
            }
            for (UUID id : batch) {
                if (!result.containsKey(id)) {
                    throw new IllegalStateException("ledger returned no clearing balance for shipment " + id);
                }
            }
        }
        return result;
    }
}
