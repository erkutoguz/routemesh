package com.routemesh.paymentservice.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.routemesh.paymentservice.FakePspClient;
import com.routemesh.paymentservice.TestPspConfiguration;
import com.routemesh.paymentservice.TestcontainersConfiguration;
import com.routemesh.paymentservice.psp.PspClient.PspPayment;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"spring.grpc.server.port=0",
        "routemesh.payment.reconciliation.scheduler-enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestPspConfiguration.class, TestJwtConfiguration.class})
class ReconciliationRestTest {

    private static final String BASE = "/api/v1/ops/payments";
    private static final AtomicInteger FAR_FUTURE = new AtomicInteger(1000);
    private static final AtomicInteger WINDOWS = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakePspClient psp;

    private final JsonMapper json = JsonMapper.builder().build();
    private final Instant base = Instant.now().minus(Duration.ofDays(60))
            .minus(Duration.ofHours(3L * WINDOWS.incrementAndGet())).truncatedTo(ChronoUnit.MICROS);

    @BeforeEach
    void reset() {
        psp.reset();
    }

    @AfterEach
    void finishLeftoverRuns() {
        jdbc.update("UPDATE reconciliation_runs SET status = 'FAILED', finished_at = now() WHERE status = 'RUNNING'");
        jdbc.update("UPDATE payment_transactions SET status = 'FAILED' WHERE status IN ('PENDING', 'IN_PROGRESS', "
                + "'UNKNOWN') AND intent_id IN (SELECT id FROM payment_intents WHERE checkout_url = 'http://x')");
    }

    // --- güvenlik ---

    @Test
    void withoutATokenNothingIsServed() throws Exception {
        mvc.perform(post(BASE + "/reconciliation-runs")).andExpect(status().isUnauthorized());
        for (String path : new String[] {"/reconciliation-runs", "/reconciliation-runs/" + UUID.randomUUID(),
                "/reconciliation-findings", "/reconciliation"}) {
            mvc.perform(get(BASE + path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void onlyOpsMayUseTheEndpoints() throws Exception {
        for (String role : new String[] {"MERCHANT", "COURIER", "WAREHOUSE", "WORKFLOW"}) {
            String token = bearer(role);
            mvc.perform(post(BASE + "/reconciliation-runs").header("Authorization", token))
                    .andExpect(status().isForbidden());
            for (String path : new String[] {"/reconciliation-runs", "/reconciliation-runs/" + UUID.randomUUID(),
                    "/reconciliation-findings", "/reconciliation"}) {
                mvc.perform(get(BASE + path).header("Authorization", token)).andExpect(status().isForbidden());
            }
        }
        mvc.perform(get(BASE + "/reconciliation").header("Authorization", ops())).andExpect(status().isOk());
    }

    @Test
    void everythingElseStaysClosedEvenForOps() throws Exception {
        mvc.perform(get("/api/v1/payments/anything").header("Authorization", ops())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/anything")).andExpect(status().isUnauthorized());
    }

    // --- tetikleme ---

    @Test
    void aManualRunStartsInTheBackgroundAndFinishesWithItsFindings() throws Exception {
        String pspId = pair("AUTHORIZED", "CAPTURED");
        pair("CAPTURED", "CAPTURED");

        MvcResult started = mvc.perform(post(BASE + "/reconciliation-runs").header("Authorization", ops())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"windowFrom\":\"" + base.minusSeconds(3600) + "\",\"windowTo\":\""
                                + base.plusSeconds(3600) + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.requestedBy").isNotEmpty())
                .andExpect(jsonPath("$.windowFrom").value(base.minusSeconds(3600).toString()))
                .andReturn();
        String id = json.readTree(started.getResponse().getContentAsString()).get("id").asString();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get(BASE + "/reconciliation-runs/" + id).header("Authorization", ops()))
                        .andExpect(jsonPath("$.run.status").value("DISCREPANCIES"))
                        .andExpect(jsonPath("$.run.pspPaymentsSeen").value(2))
                        .andExpect(jsonPath("$.run.findingsCount").value(1))
                        .andExpect(jsonPath("$.findingsByType.STATUS_MISMATCH").value(1))
                        .andExpect(jsonPath("$.findingsBySeverity.CRITICAL").value(1)));
        mvc.perform(get(BASE + "/reconciliation-findings?runId=" + id).header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].pspPaymentId").value(pspId))
                .andExpect(jsonPath("$.items[0].localStatus").value("AUTHORIZED"))
                .andExpect(jsonPath("$.items[0].pspStatus").value("CAPTURED"))
                .andExpect(jsonPath("$.items[0].detail.expectedPspStatuses[0]").value("AUTHORIZED"));
    }

    @Test
    void theRequestBodyIsOptionalAndTheDefaultWindowApplies() throws Exception {
        mvc.perform(post(BASE + "/reconciliation-runs").header("Authorization", ops()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        waitForNoActiveRun();
    }

    @Test
    void startingARunWhileAnotherIsRunningIsAConflict() throws Exception {
        jdbc.update("INSERT INTO reconciliation_runs (trigger_source, window_from, window_to) "
                + "VALUES ('SCHEDULED', now() - interval '1 hour', now())");

        mvc.perform(post(BASE + "/reconciliation-runs").header("Authorization", ops()))
                .andExpect(status().isConflict());
        mvc.perform(get(BASE + "/reconciliation").header("Authorization", ops()))
                .andExpect(jsonPath("$.activeRunId").isNotEmpty());
    }

    @Test
    void invalidWindowsAreRejected() throws Exception {
        Instant now = Instant.now();
        for (String body : new String[] {
                "{\"windowTo\":\"" + now.plus(Duration.ofHours(2)) + "\"}",
                "{\"windowFrom\":\"" + now.minusSeconds(10) + "\",\"windowTo\":\"" + now.minusSeconds(10) + "\"}",
                "{\"windowFrom\":\"" + now.minusSeconds(10) + "\",\"windowTo\":\"" + now.minusSeconds(100) + "\"}",
                "{\"windowFrom\":\"" + now.plusSeconds(600) + "\"}",
                "{\"windowFrom\":\"" + now.minus(Duration.ofDays(8)) + "\",\"windowTo\":\"" + now.minusSeconds(60) + "\"}",
                "{\"windowFrom\":\"yesterday\"}"}) {
            mvc.perform(post(BASE + "/reconciliation-runs").header("Authorization", ops())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs WHERE status = 'RUNNING'",
                Integer.class)).isZero();
    }

    // --- geçmiş ---

    @Test
    void runsPageWithAnOpaqueCursorNewestFirstAndCanBeFilteredByStatus() throws Exception {
        List<UUID> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mine.add(insertRun(i % 2 == 0 ? "OK" : "DISCREPANCIES", Instant.parse("2001-01-01T00:00:00Z").plusSeconds(i)));
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            MvcResult result = mvc.perform(get(BASE + "/reconciliation-runs?limit=2" + (cursor == null ? ""
                    : "&cursor=" + cursor)).header("Authorization", ops())).andExpect(status().isOk()).andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            body.get("items").forEach(item -> seen.add(item.get("id").asString()));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asString();
        } while (cursor != null);

        assertThat(seen).doesNotHaveDuplicates();
        List<String> ours = seen.stream().filter(id -> mine.contains(UUID.fromString(id))).toList();
        assertThat(ours).containsExactly(mine.get(4).toString(), mine.get(3).toString(), mine.get(2).toString(),
                mine.get(1).toString(), mine.get(0).toString());
        MvcResult failed = mvc.perform(get(BASE + "/reconciliation-runs?status=DISCREPANCIES&limit=100")
                .header("Authorization", ops())).andExpect(status().isOk()).andReturn();
        json.readTree(failed.getResponse().getContentAsString()).get("items")
                .forEach(item -> assertThat(item.get("status").asString()).isEqualTo("DISCREPANCIES"));
    }

    @Test
    void anUnknownRunAndBadParametersAreHandled() throws Exception {
        mvc.perform(get(BASE + "/reconciliation-runs/" + UUID.randomUUID()).header("Authorization", ops()))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/reconciliation-runs/not-a-uuid").header("Authorization", ops()))
                .andExpect(status().isBadRequest());
        for (String path : new String[] {"/reconciliation-runs?limit=0", "/reconciliation-runs?limit=101",
                "/reconciliation-runs?cursor=zzz", "/reconciliation-runs?cursor=bm9wZQ",
                "/reconciliation-runs?status=NOPE", "/reconciliation-findings?limit=0",
                "/reconciliation-findings?type=NOPE", "/reconciliation-findings?severity=FATAL",
                "/reconciliation-findings?cursor=0", "/reconciliation-findings?runId=x"}) {
            mvc.perform(get(BASE + path).header("Authorization", ops())).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE + "/reconciliation-runs?limit=100").header("Authorization", ops()))
                .andExpect(status().isOk());
    }

    @Test
    void errorsArriveAsProblemDetails() throws Exception {
        mvc.perform(get(BASE + "/reconciliation-runs?limit=0").header("Authorization", ops()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists());
    }

    // --- bulgular ---

    @Test
    void findingsAreFilteredByRunTypeAndSeverityAndPagedByIdNewestFirst() throws Exception {
        UUID run = insertRun("DISCREPANCIES", Instant.now().plus(Duration.ofDays(FAR_FUTURE.incrementAndGet())));
        for (int i = 0; i < 5; i++) {
            insertFinding(run, i < 3 ? "STATUS_MISMATCH" : "STUCK_TRANSACTION", i < 3 ? "CRITICAL" : "WARNING",
                    "psp_f" + i);
        }
        UUID other = insertRun("OK", Instant.now().plus(Duration.ofDays(FAR_FUTURE.incrementAndGet())));
        insertFinding(other, "MISSING_AT_PSP", "CRITICAL", "psp_other");

        mvc.perform(get(BASE + "/reconciliation-findings?runId=" + run).header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].pspPaymentId").value("psp_f4"));
        mvc.perform(get(BASE + "/reconciliation-findings?runId=" + run + "&type=STATUS_MISMATCH")
                        .header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get(BASE + "/reconciliation-findings?runId=" + run + "&severity=WARNING")
                        .header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].type").value("STUCK_TRANSACTION"));
        MvcResult first = mvc.perform(get(BASE + "/reconciliation-findings?runId=" + run + "&limit=2")
                        .header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isString()).andReturn();
        String cursor = json.readTree(first.getResponse().getContentAsString()).get("nextCursor").asString();
        mvc.perform(get(BASE + "/reconciliation-findings?runId=" + run + "&limit=2&cursor=" + cursor)
                        .header("Authorization", ops()))
                .andExpect(jsonPath("$.items[0].pspPaymentId").value("psp_f2"))
                .andExpect(jsonPath("$.items[1].pspPaymentId").value("psp_f1"));
    }

    // --- özet ---

    @Test
    void theSummaryFollowsTheLatestFinishedRun() throws Exception {
        UUID failed = insertRun("FAILED", Instant.now().plus(Duration.ofDays(FAR_FUTURE.incrementAndGet())));
        insertFinding(failed, "PSP_REPORT_UNAVAILABLE", "CRITICAL", null);
        mvc.perform(get(BASE + "/reconciliation").header("Authorization", ops()))
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.latestRun.id").value(failed.toString()))
                .andExpect(jsonPath("$.findingsByType.PSP_REPORT_UNAVAILABLE").value(1))
                .andExpect(jsonPath("$.findingsBySeverity.CRITICAL").value(1));

        UUID discrepancies = insertRun("DISCREPANCIES", Instant.now().plus(Duration.ofDays(FAR_FUTURE.incrementAndGet())));
        insertFinding(discrepancies, "STATUS_MISMATCH", "CRITICAL", "psp_x");
        insertFinding(discrepancies, "STUCK_TRANSACTION", "WARNING", "psp_y");
        mvc.perform(get(BASE + "/reconciliation").header("Authorization", ops()))
                .andExpect(jsonPath("$.status").value("DISCREPANCIES"))
                .andExpect(jsonPath("$.latestRun.findingsCount").value(0))
                .andExpect(jsonPath("$.findingsByType.STATUS_MISMATCH").value(1))
                .andExpect(jsonPath("$.findingsBySeverity.WARNING").value(1));

        UUID ok = insertRun("OK", Instant.now().plus(Duration.ofDays(FAR_FUTURE.incrementAndGet())));
        mvc.perform(get(BASE + "/reconciliation").header("Authorization", ops()))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.latestRun.id").value(ok.toString()))
                .andExpect(jsonPath("$.findingsByType").isEmpty())
                .andExpect(jsonPath("$.lastCompletedWindowEnd").isNotEmpty());
    }

    // --- yardımcılar ---

    private void waitForNoActiveRun() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation_runs WHERE status = 'RUNNING'", Integer.class)).isZero());
    }

    private UUID insertRun(String status, Instant startedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reconciliation_runs (id, trigger_source, status, started_at, finished_at, "
                        + "window_from, window_to) VALUES (?, 'SCHEDULED', ?, ?, ?, ?, ?)", id, status,
                Timestamp.from(startedAt), Timestamp.from(startedAt.plusSeconds(1)),
                Timestamp.from(startedAt.minusSeconds(3600)), Timestamp.from(startedAt));
        return id;
    }

    private void insertFinding(UUID runId, String type, String severity, String pspPaymentId) {
        jdbc.update("INSERT INTO reconciliation_findings (run_id, type, severity, psp_payment_id) "
                + "VALUES (?, ?, ?, ?)", runId, type, severity, pspPaymentId);
    }

    private String pair(String localStatus, String pspStatus) {
        String id = "psp_rest_" + UUID.randomUUID();
        jdbc.update("INSERT INTO payment_intents (idempotency_key, request_hash, shipment_id, amount_minor, currency, "
                        + "status, psp_payment_id, checkout_url, captured_minor, refunded_minor, expires_at, "
                        + "created_at, updated_at) VALUES (?, 'h', ?, 1000, 'TRY', ?, ?, 'http://x', 0, 0, ?, ?, ?)",
                UUID.randomUUID().toString(), UUID.randomUUID(), localStatus, id, Timestamp.from(base.plusSeconds(900)),
                Timestamp.from(base), Timestamp.from(base));
        psp.pspHas(new PspPayment(id, "ref-" + id, pspStatus, 1000, "CAPTURED".equals(pspStatus) ? 0 : 0, 0, base,
                base));
        return id;
    }

    private String ops() {
        return bearer("OPS");
    }

    private static String bearer(String role) {
        return "Bearer " + TestJwts.token(UUID.randomUUID().toString(), role);
    }
}
