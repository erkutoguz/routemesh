package com.routemesh.pspsimulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
@SpringBootTest(properties = {"routemesh.sim.retry-delays=100ms", "routemesh.sim.webhook-secret=test-secret"})
class SimulatorReportTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private SimFaults faults;

    private final List<String> deliveries = new CopyOnWriteArrayList<>();
    private HttpServer receiver;
    private String callbackUrl;

    @BeforeEach
    void startReceiver() throws IOException {
        faults.dropWebhooks(false);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            deliveries.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();
        callbackUrl = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void stopReceiver() {
        faults.dropWebhooks(false);
        receiver.stop(0);
    }

    // --- rapor ---

    @Test
    void aNewPaymentAppearsInTheReportWithTimestamps() throws Exception {
        Instant before = Instant.now();
        String id = create(1001);

        JsonNode item = reportItem(id);
        assertThat(item.get("status").asString()).isEqualTo("CREATED");
        assertThat(item.get("amount_minor").asLong()).isEqualTo(1001);
        assertThat(item.get("captured_minor").asLong()).isZero();
        assertThat(item.get("refunded_minor").asLong()).isZero();
        assertThat(item.get("reference").asString()).startsWith("ref-");
        assertThat(Instant.parse(item.get("created_at").asString())).isAfterOrEqualTo(before.minusMillis(1));
        assertThat(item.get("updated_at").asString()).isEqualTo(item.get("created_at").asString());
    }

    @Test
    void theReportFollowsTheLifecycleAndUpdatedAtMovesForward() throws Exception {
        String id = create(2000);
        Instant created = Instant.parse(reportItem(id).get("updated_at").asString());

        Thread.sleep(5);
        mvc.perform(post("/v1/sim/payments/" + id + "/complete")).andExpect(status().isAccepted());
        Instant authorized = Instant.parse(reportItem(id).get("updated_at").asString());
        assertThat(reportItem(id).get("status").asString()).isEqualTo("AUTHORIZED");
        assertThat(authorized).isAfter(created);

        Thread.sleep(5);
        mvc.perform(post("/v1/payments/" + id + "/capture").header("Idempotency-Key", "c-" + id))
                .andExpect(status().isOk());
        JsonNode captured = reportItem(id);
        assertThat(captured.get("status").asString()).isEqualTo("CAPTURED");
        assertThat(captured.get("captured_minor").asLong()).isEqualTo(2000);
        assertThat(Instant.parse(captured.get("updated_at").asString())).isAfter(authorized);

        Thread.sleep(5);
        mvc.perform(post("/v1/payments/" + id + "/refunds").header("Idempotency-Key", "r-" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount_minor\":500}"))
                .andExpect(status().isOk());
        JsonNode refunded = reportItem(id);
        assertThat(refunded.get("refunded_minor").asLong()).isEqualTo(500);
        assertThat(refunded.get("captured_minor").asLong()).isEqualTo(2000);
    }

    @Test
    void theReportPagesByUpdatedAtAndIdWithAnOpaqueCursor() throws Exception {
        Thread.sleep(25);
        Instant from = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Thread.sleep(25);
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            created.add(create(100 + i));
            Thread.sleep(2);
        }
        Instant to = Instant.now().plusSeconds(1);

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = report("updatedFrom=" + from + "&updatedTo=" + to + "&limit=3"
                    + (cursor == null ? "" : "&cursor=" + cursor));
            page.get("items").forEach(item -> seen.add(item.get("id").asString()));
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(created);
    }

    @Test
    void fromIsInclusiveAndToIsExclusive() throws Exception {
        String id = create(300);
        Instant updated = Instant.parse(reportItem(id).get("updated_at").asString());

        assertThat(ids(report("updatedFrom=" + updated + "&limit=500"))).contains(id);
        assertThat(ids(report("updatedFrom=" + updated.plusNanos(1000) + "&limit=500"))).doesNotContain(id);
        assertThat(ids(report("updatedTo=" + updated + "&limit=500"))).doesNotContain(id);
        assertThat(ids(report("updatedTo=" + updated.plusNanos(1000) + "&limit=500"))).contains(id);
    }

    @Test
    void invalidReportParametersAreBadRequests() throws Exception {
        Instant now = Instant.now();
        for (String query : new String[] {"limit=0", "limit=501", "limit=abc", "cursor=%25%25", "cursor=bm9wZQ",
                "updatedFrom=yesterday", "updatedFrom=" + now + "&updatedTo=" + now,
                "updatedFrom=" + now.plusSeconds(5) + "&updatedTo=" + now}) {
            mvc.perform(get("/v1/reports/payments?" + query)).andExpect(status().isBadRequest());
        }
    }

    // --- hata enjeksiyonu: webhook'lar ---

    @Test
    void droppingWebhooksSilencesTheCallbackWhileThePaymentStateStillAdvances() throws Exception {
        mvc.perform(post("/v1/sim/faults").contentType(MediaType.APPLICATION_JSON).content("{\"dropWebhooks\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.drop_webhooks").value(true));
        mvc.perform(get("/v1/sim/faults")).andExpect(jsonPath("$.drop_webhooks").value(true));
        String id = create(900);

        mvc.perform(post("/v1/sim/payments/" + id + "/complete")).andExpect(status().isAccepted());
        mvc.perform(post("/v1/payments/" + id + "/capture").header("Idempotency-Key", "c-" + id))
                .andExpect(status().isOk());

        Thread.sleep(600);
        assertThat(deliveries).isEmpty();
        assertThat(reportItem(id).get("status").asString()).isEqualTo("CAPTURED");

        mvc.perform(post("/v1/sim/faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"drop_webhooks\":false}"))
                .andExpect(jsonPath("$.drop_webhooks").value(false));
        String second = create(901);
        mvc.perform(post("/v1/sim/payments/" + second + "/complete")).andExpect(status().isAccepted());
        awaitDelivery();
        assertThat(deliveries).hasSize(1).first().asString().contains("payment.authorized");
    }

    @Test
    void settingFaultsWithoutTheFlagKeepsTheCurrentValue() throws Exception {
        faults.dropWebhooks(true);

        mvc.perform(post("/v1/sim/faults").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.drop_webhooks").value(true));
    }

    // --- hata enjeksiyonu: tamper ---

    @Test
    void tamperChangesTheAmountsAndStatusWithoutAnyWebhook() throws Exception {
        String id = create(1000);
        mvc.perform(post("/v1/sim/payments/" + id + "/complete")).andExpect(status().isAccepted());
        mvc.perform(post("/v1/payments/" + id + "/capture").header("Idempotency-Key", "c-" + id))
                .andExpect(status().isOk());
        awaitDeliveries(2);
        Instant before = Instant.parse(reportItem(id).get("updated_at").asString());

        Thread.sleep(5);
        mvc.perform(post("/v1/sim/payments/" + id + "/tamper").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capturedMinor\":900,\"refundedMinor\":250,\"status\":\"CAPTURED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captured_minor").value(900))
                .andExpect(jsonPath("$.refunded_minor").value(250));

        JsonNode item = reportItem(id);
        assertThat(item.get("captured_minor").asLong()).isEqualTo(900);
        assertThat(item.get("refunded_minor").asLong()).isEqualTo(250);
        assertThat(Instant.parse(item.get("updated_at").asString())).isAfter(before);
        mvc.perform(get("/v1/payments/" + id)).andExpect(jsonPath("$.refunded_minor").value(250));
        Thread.sleep(400);
        assertThat(deliveries).hasSize(2);
    }

    @Test
    void tamperOnlyChangesTheFieldsThatAreGiven() throws Exception {
        String id = create(1000);

        mvc.perform(post("/v1/sim/payments/" + id + "/tamper").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"VOIDED\"}"))
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.captured_minor").value(0));
        mvc.perform(post("/v1/sim/payments/" + id + "/tamper").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"captured_minor\":10}"))
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.captured_minor").value(10));
    }

    @Test
    void invalidTamperRequestsAreRejected() throws Exception {
        String id = create(1000);

        mvc.perform(post("/v1/sim/payments/psp_missing/tamper").contentType(MediaType.APPLICATION_JSON)
                        .content("{}")).andExpect(status().isNotFound());
        mvc.perform(post("/v1/sim/payments/" + id + "/tamper").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NOPE\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/sim/payments/" + id + "/tamper").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capturedMinor\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/sim/payments/" + id + "/tamper").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refunded_minor\":-5}")).andExpect(status().isBadRequest());
    }

    // --- yardımcılar ---

    private String create(long amount) throws Exception {
        MvcResult result = mvc.perform(post("/v1/payments").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_minor\":" + amount + ",\"currency\":\"TRY\",\"callback_url\":\""
                                + callbackUrl + "\",\"reference\":\"ref-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    private JsonNode report(String query) throws Exception {
        MvcResult result = mvc.perform(get("/v1/reports/payments?" + query)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode reportItem(String id) throws Exception {
        String cursor = null;
        do {
            JsonNode page = report("limit=500" + (cursor == null ? "" : "&cursor=" + cursor));
            for (JsonNode item : page.get("items")) {
                if (id.equals(item.get("id").asString())) {
                    return item;
                }
            }
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
        } while (cursor != null);
        throw new AssertionError("payment " + id + " is not in the report");
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    private void awaitDelivery() throws InterruptedException {
        awaitDeliveries(1);
    }

    private void awaitDeliveries(int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (deliveries.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(deliveries.size()).isGreaterThanOrEqualTo(count);
    }
}
