package com.routemesh.paymentservice.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.routemesh.paymentservice.FakeLedgerClearingClient;
import com.routemesh.paymentservice.FakePspClient;
import com.routemesh.paymentservice.TestPspConfiguration;
import com.routemesh.paymentservice.TestcontainersConfiguration;
import com.routemesh.paymentservice.psp.PspClient.PspPayment;
import com.routemesh.paymentservice.reconciliation.ReconciliationRepository.Run;
import com.routemesh.paymentservice.reconciliation.ReconciliationService.Trigger;

@Import({TestcontainersConfiguration.class, TestPspConfiguration.class})
@SpringBootTest(properties = {
        "spring.grpc.server.port=0",
        "routemesh.payment.reconciliation.scheduler-enabled=false",
        "routemesh.payment.reconciliation.ledger-check-enabled=false"})
class ReconciliationLedgerDisabledTest {

    @Autowired
    private ReconciliationService service;

    @Autowired
    private FakeLedgerClearingClient ledger;

    @Autowired
    private FakePspClient psp;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void withTheLedgerCheckSwitchedOffTheLedgerIsNeverAskedAndNothingIsReported() {
        Instant base = Instant.now().minus(Duration.ofDays(10)).truncatedTo(ChronoUnit.MICROS);
        UUID shipment = UUID.randomUUID();
        String pspId = "psp_off_" + UUID.randomUUID();
        jdbc.update("INSERT INTO payment_intents (idempotency_key, request_hash, shipment_id, amount_minor, currency, "
                        + "status, psp_payment_id, checkout_url, captured_minor, refunded_minor, expires_at, "
                        + "created_at, updated_at) VALUES (?, 'h', ?, 1000, 'TRY', 'CAPTURED', ?, 'http://x', 1000, 0, "
                        + "?, ?, ?)", UUID.randomUUID().toString(), shipment, pspId,
                Timestamp.from(base.plusSeconds(900)), Timestamp.from(base), Timestamp.from(base));
        psp.reset();
        psp.pspHas(new PspPayment(pspId, "ref", "CAPTURED", 1000, 1000, 0, base, base));
        ledger.reset();
        ledger.ledgerHas(shipment, 0);

        Run run = service.run(Trigger.MANUAL, "ops@test", base.minusSeconds(3600), base.plusSeconds(3600));

        assertThat(run.status()).isEqualTo("OK");
        assertThat(ledger.calls()).isEmpty();
    }
}
