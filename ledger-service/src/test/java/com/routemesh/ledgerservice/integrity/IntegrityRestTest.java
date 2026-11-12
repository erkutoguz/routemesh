package com.routemesh.ledgerservice.integrity;

import static com.routemesh.ledgerservice.integrity.IntegrityTestSupport.ESCROW;
import static com.routemesh.ledgerservice.integrity.IntegrityTestSupport.PSP;
import static com.routemesh.ledgerservice.integrity.IntegrityTestSupport.raw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.ledgerservice.TestcontainersConfiguration;
import com.routemesh.ledgerservice.service.LedgerService;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"spring.grpc.server.port=0", "routemesh.ledger.integrity.scheduler-enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class IntegrityRestTest {

    private static final String BASE = "/api/v1/ops/ledger";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private LedgerService ledger;

    private final JsonMapper json = JsonMapper.builder().build();
    private IntegrityTestSupport support;

    @BeforeEach
    void reset() {
        support = new IntegrityTestSupport(jdbc, tx, ledger);
        support.reset();
    }

    // --- güvenlik ---

    @Test
    void withoutATokenNothingIsServed() throws Exception {
        mvc.perform(get(BASE + "/integrity")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/integrity-runs")).andExpect(status().is4xxClientError());
        mvc.perform(get(BASE + "/findings")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/checkpoints")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyOpsMayUseTheEndpoints() throws Exception {
        for (String role : new String[] {"MERCHANT", "COURIER", "WAREHOUSE", "WORKFLOW"}) {
            String token = bearer(role);
            mvc.perform(get(BASE + "/integrity").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/integrity-runs").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/findings").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/checkpoints").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/integrity-runs").header("Authorization", token)).andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE + "/integrity").header("Authorization", bearer("OPS"))).andExpect(status().isOk());
    }

    // --- koşular ---

    @Test
    void anIncrementalRunIsSynchronousAndIsVisibleInTheLists() throws Exception {
        support.capture(UUID.randomUUID(), 1001);

        MvcResult started = mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"INCREMENTAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.mode").value("INCREMENTAL"))
                .andExpect(jsonPath("$.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.entriesScanned").value(2))
                .andExpect(jsonPath("$.requestedBy").exists()).andReturn();
        String id = json.readTree(started.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(get(BASE + "/integrity-runs/" + id).header("Authorization", ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value(id))
                .andExpect(jsonPath("$.findingsByType").isEmpty());
        mvc.perform(get(BASE + "/checkpoints").header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].entryCount").value(2))
                .andExpect(jsonPath("$.items[0].chainHash").isString());
    }

    @Test
    void theModeDefaultsToIncrementalAndABadModeIsRejected() throws Exception {
        mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("INCREMENTAL"));
        mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"NOPE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aFullRunStartsInTheBackgroundAndFinishes() throws Exception {
        support.capture(UUID.randomUUID(), 1001);
        mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops())).andExpect(status().isOk());

        MvcResult started = mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"FULL\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mode").value("FULL")).andReturn();
        String id = json.readTree(started.getResponse().getContentAsString()).get("id").asString();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                mvc.perform(get(BASE + "/integrity-runs/" + id).header("Authorization", ops()))
                        .andExpect(jsonPath("$.run.status").value("OK"))
                        .andExpect(jsonPath("$.run.entriesScanned").value(2)));
    }

    @Test
    void startingARunWhileTheSameModeIsRunningIsAConflict() throws Exception {
        jdbc.update("INSERT INTO integrity_runs (mode, trigger_source) VALUES ('FULL', 'SCHEDULED')");

        mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"FULL\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops())).andExpect(status().isOk());
    }

    @Test
    void runsPageWithAnOpaqueCursorAndFilters() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops())).andExpect(status().isOk());
        }
        java.util.List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        do {
            MvcResult result = mvc.perform(get(BASE + "/integrity-runs?limit=2" + (cursor == null ? "" : "&cursor=" + cursor))
                    .header("Authorization", ops())).andExpect(status().isOk()).andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            body.get("items").forEach(item -> seen.add(item.get("id").asString()));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asString();
        } while (cursor != null);

        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        mvc.perform(get(BASE + "/integrity-runs?mode=FULL").header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(BASE + "/integrity-runs?status=OK&limit=100").header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(5));
    }

    @Test
    void unknownRunAndBadParametersAreHandled() throws Exception {
        mvc.perform(get(BASE + "/integrity-runs/" + UUID.randomUUID()).header("Authorization", ops()))
                .andExpect(status().isNotFound());
        for (String path : new String[] {"/integrity-runs?limit=0", "/integrity-runs?limit=101",
                "/integrity-runs?cursor=zzz", "/integrity-runs?mode=NOPE", "/integrity-runs?status=NOPE",
                "/findings?limit=0", "/findings?cursor=0", "/findings?runId=x", "/checkpoints?cursor=-1",
                "/checkpoints?limit=abc"}) {
            mvc.perform(get(BASE + path).header("Authorization", ops())).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE + "/integrity-runs/not-a-uuid").header("Authorization", ops()))
                .andExpect(status().isBadRequest());
    }

    // --- bulgular ve özet ---

    @Test
    void findingsAreListedWithFiltersAndAnAlarmSummary() throws Exception {
        UUID shipment = UUID.randomUUID();
        support.rawTransaction("BROKEN", shipment, null, raw(PSP, "DEBIT", 900), raw(ESCROW, "CREDIT", 500));
        support.rawTransaction("PAYOUT", null, null, raw("LIABILITY:MERCHANT_WALLET:" + UUID.randomUUID(), "DEBIT", 70),
                raw(PSP, "CREDIT", 70));

        MvcResult started = mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VIOLATIONS")).andReturn();
        String runId = json.readTree(started.getResponse().getContentAsString()).get("id").asString();

        mvc.perform(get(BASE + "/integrity-runs/" + runId).header("Authorization", ops()))
                .andExpect(jsonPath("$.findingsByType.UNBALANCED_TRANSACTION").value(1))
                .andExpect(jsonPath("$.findingsByType.NEGATIVE_BALANCE").value(1))
                .andExpect(jsonPath("$.findingsByType.TOTALS_MISMATCH").value(1));
        mvc.perform(get(BASE + "/findings?runId=" + runId + "&type=NEGATIVE_BALANCE").header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.items[0].actualMinor").value(70))
                .andExpect(jsonPath("$.items[0].detail.meaning").exists());
        mvc.perform(get(BASE + "/findings?runId=" + runId + "&limit=2").header("Authorization", ops()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isString());
        mvc.perform(get(BASE + "/integrity").header("Authorization", ops()))
                .andExpect(jsonPath("$.status").value("VIOLATIONS"))
                .andExpect(jsonPath("$.latestIncremental.id").value(runId))
                .andExpect(jsonPath("$.negativeBalances.length()").value(1))
                .andExpect(jsonPath("$.latestCheckpoint.entryCount").value(4));
    }

    @Test
    void theSummaryGoesFromUnknownToHealthy() throws Exception {
        mvc.perform(get(BASE + "/integrity").header("Authorization", ops()))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.latestCheckpoint").doesNotExist());

        support.capture(UUID.randomUUID(), 1001);
        mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops())).andExpect(status().isOk());

        mvc.perform(get(BASE + "/integrity").header("Authorization", ops()))
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.negativeBalances.length()").value(0))
                .andExpect(jsonPath("$.latestIncremental.status").value("OK"));
    }

    @Test
    void anAuditThatCouldNotFinishMakesTheSummaryDegradedNotViolated() throws Exception {
        support.capture(UUID.randomUUID(), 1001);
        mvc.perform(post(BASE + "/integrity-runs").header("Authorization", ops())).andExpect(status().isOk());
        jdbc.update("INSERT INTO integrity_runs (mode, trigger_source, status, finished_at, error) "
                + "VALUES ('FULL', 'SCHEDULED', 'FAILED', now(), 'boom')");

        mvc.perform(get(BASE + "/integrity").header("Authorization", ops()))
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.latestFull.error").value("boom"));
    }

    private String ops() {
        return bearer("OPS");
    }

    private static String bearer(String role) {
        return "Bearer " + TestJwts.token(UUID.randomUUID().toString(), role);
    }
}
