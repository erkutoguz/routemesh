package com.routemesh.paymentservice;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;

import com.routemesh.paymentservice.ledger.LedgerClearingClient;

/**
 * Testlerde defter: varsayılan olarak yerel ödemelerle tutarlıdır (her gönderi için {@code captured - refunded}
 * toplamını döner); bir gönderi için başka değer ya da hata ayarlanarak uyuşmazlık üretilir.
 */
public class FakeLedgerClearingClient implements LedgerClearingClient {

    private final JdbcTemplate jdbc;
    private final Map<UUID, Long> overrides = new ConcurrentHashMap<>();
    private final List<List<UUID>> calls = Collections.synchronizedList(new ArrayList<>());
    private volatile RuntimeException failure;

    public FakeLedgerClearingClient(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ledgerHas(UUID shipmentId, long netDebitMinor) {
        overrides.put(shipmentId, netDebitMinor);
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    public List<List<UUID>> calls() {
        return new ArrayList<>(calls);
    }

    public List<UUID> askedShipments() {
        return calls().stream().flatMap(List::stream).toList();
    }

    public void reset() {
        overrides.clear();
        calls.clear();
        failure = null;
    }

    @Override
    public Map<UUID, Long> clearingNetDebit(Collection<UUID> shipmentIds) {
        calls.add(new ArrayList<>(shipmentIds));
        if (failure != null) {
            throw failure;
        }
        Map<UUID, Long> result = new LinkedHashMap<>();
        for (UUID id : new LinkedHashSet<>(shipmentIds)) {
            Long override = overrides.get(id);
            result.put(id, override != null ? override : jdbc.queryForObject(
                    "SELECT COALESCE(SUM(captured_minor - refunded_minor), 0) FROM payment_intents "
                            + "WHERE shipment_id = ?", Long.class, id));
        }
        return result;
    }
}
