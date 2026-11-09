package com.routemesh.pspsimulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
@SpringBootTest(properties = {"routemesh.sim.retry-delays=150ms,150ms,150ms",
        "routemesh.sim.webhook-secret=test-secret"})
class SimulatorFlowTest {

    private record Delivery(String signature, String body, long receivedAtMillis) {
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private HttpServer receiver;
    private String callbackUrl;

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            deliveries.add(new Delivery(exchange.getRequestHeaders().getFirst("X-PSP-Signature"), body,
                    System.currentTimeMillis()));
            int code = failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? 500 : 200;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        receiver.start();
        callbackUrl = "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    @Test
    void createPayment_isIdempotentAndReturnsACheckoutUrl() throws Exception {
        String key = "k-" + System.nanoTime();

        String first = create(key, 1001).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amount_minor").value(1001))
                .andReturn().getResponse().getContentAsString();
        String second = create(key, 1001).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();

        assertThat(id(second)).isEqualTo(id(first));
        assertThat(jsonMapper.readTree(first).path("checkout_url").asString()).contains(id(first));
    }

    @Test
    void invalidRequests_areRejected() throws Exception {
        mockMvc.perform(post("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount_minor\":1,\"currency\":\"TRY\",\"callback_url\":\"http://x\"}"))
                .andExpect(status().isBadRequest());
        create("k-" + System.nanoTime(), 0).andExpect(status().isUnprocessableContent());
        mockMvc.perform(get("/v1/payments/psp_missing")).andExpect(status().isNotFound());
    }

    @Test
    void complete_sendsASignedAuthorizedWebhook() throws Exception {
        String id = createPayment(1001);

        complete(id, "{\"outcome\":\"SUCCESS\"}").andExpect(status().isAccepted());

        Delivery delivery = awaitDeliveries(1).get(0);
        JsonNode event = jsonMapper.readTree(delivery.body());
        assertThat(event.path("type").asString()).isEqualTo("payment.authorized");
        assertThat(event.path("id").asString()).startsWith("evt_");
        assertThat(event.path("data").path("payment_id").asString()).isEqualTo(id);
        assertThat(event.path("data").path("amount_minor").asLong()).isEqualTo(1001);
        assertThat(event.path("data").path("currency").asString()).isEqualTo("TRY");
        assertThat(delivery.signature()).matches("t=\\d+,v1=[0-9a-f]{64}");
        assertThat(delivery.signature()).isEqualTo(expectedSignature(delivery));
    }

    @Test
    void complete_withDecline_sendsADeclinedWebhook() throws Exception {
        String id = createPayment(500);

        complete(id, "{\"outcome\":\"DECLINE\"}").andExpect(status().isAccepted());

        JsonNode event = jsonMapper.readTree(awaitDeliveries(1).get(0).body());
        assertThat(event.path("type").asString()).isEqualTo("payment.declined");
        assertThat(event.path("data").path("decline_reason").asString()).isEqualTo("card_declined");
        mockMvc.perform(get("/v1/payments/" + id)).andExpect(jsonPath("$.status").value("DECLINED"));
    }

    @Test
    void complete_twice_isAConflict() throws Exception {
        String id = createPayment(500);
        complete(id, null).andExpect(status().isAccepted());

        complete(id, null).andExpect(status().isConflict());
    }

    @Test
    void duplicateCount_sendsTheSameEventSeveralTimes() throws Exception {
        String id = createPayment(500);

        complete(id, "{\"outcome\":\"SUCCESS\",\"duplicate_count\":3}").andExpect(status().isAccepted());

        List<Delivery> received = awaitDeliveries(3);
        assertThat(received.stream().map(d -> jsonMapper.readTree(d.body()).path("id").asString()).distinct())
                .hasSize(1);
    }

    @Test
    void delay_postponesTheDelivery() throws Exception {
        String id = createPayment(500);
        long started = System.currentTimeMillis();

        complete(id, "{\"outcome\":\"SUCCESS\",\"delay_seconds\":1}").andExpect(status().isAccepted());

        Delivery delivery = awaitDeliveries(1).get(0);
        assertThat(delivery.receivedAtMillis() - started).isGreaterThanOrEqualTo(900);
    }

    @Test
    void failedDeliveries_areRetriedWithTheSameEventId() throws Exception {
        String id = createPayment(500);
        failuresLeft.set(2);

        complete(id, "{\"outcome\":\"SUCCESS\"}").andExpect(status().isAccepted());

        List<Delivery> received = awaitDeliveries(3);
        assertThat(received.stream().map(d -> jsonMapper.readTree(d.body()).path("id").asString()).distinct())
                .hasSize(1);
        Thread.sleep(500);
        assertThat(deliveries).hasSize(3);
    }

    @Test
    void capture_isIdempotentAndOnlyWorksOnAnAuthorizedPayment() throws Exception {
        String id = createPayment(1001);
        capture(id, "c1").andExpect(status().isConflict());
        complete(id, null).andExpect(status().isAccepted());
        awaitDeliveries(1);

        capture(id, "c1").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        capture(id, "c1").andExpect(status().isOk());
        capture(id, "c2").andExpect(status().isConflict());

        List<Delivery> received = awaitDeliveries(2);
        assertThat(jsonMapper.readTree(received.get(1).body()).path("type").asString()).isEqualTo("payment.captured");
        Thread.sleep(300);
        assertThat(deliveries).hasSize(2);
    }

    @Test
    void void_releasesAnAuthorizedPaymentOnce() throws Exception {
        String id = createPayment(1001);
        complete(id, null).andExpect(status().isAccepted());
        awaitDeliveries(1);

        mockMvc.perform(post("/v1/payments/" + id + "/void").header("Idempotency-Key", "v1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOIDED"));
        mockMvc.perform(post("/v1/payments/" + id + "/void").header("Idempotency-Key", "v1"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/v1/payments/" + id + "/void").header("Idempotency-Key", "v2"))
                .andExpect(status().isConflict());
        assertThat(jsonMapper.readTree(awaitDeliveries(2).get(1).body()).path("type").asString())
                .isEqualTo("payment.voided");
    }

    @Test
    void refunds_cannotExceedTheCapturedAmount_andAreIdempotent() throws Exception {
        String id = createPayment(1001);
        complete(id, null).andExpect(status().isAccepted());
        capture(id, "c1").andExpect(status().isOk());

        refund(id, "r1", 400).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        refund(id, "r1", 400).andExpect(status().isOk());
        refund(id, "r2", 700).andExpect(status().isUnprocessableContent());
        refund(id, "r3", 601).andExpect(status().isOk());
        refund(id, "r4", 1).andExpect(status().isUnprocessableContent());

        mockMvc.perform(get("/v1/payments/" + id)).andExpect(jsonPath("$.refunded_minor").value(1001));
        List<Delivery> received = awaitDeliveries(4);
        long refundEvents = received.stream()
                .filter(d -> jsonMapper.readTree(d.body()).path("type").asString().equals("refund.succeeded"))
                .count();
        assertThat(refundEvents).isEqualTo(2);
    }

    @Test
    void refund_beforeCapture_isAConflict() throws Exception {
        String id = createPayment(1001);
        complete(id, null).andExpect(status().isAccepted());

        refund(id, "r1", 100).andExpect(status().isConflict());
    }

    @Test
    void mutatingCalls_requireAnIdempotencyKey() throws Exception {
        String id = createPayment(1001);

        mockMvc.perform(post("/v1/payments/" + id + "/capture")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/payments/" + id + "/void")).andExpect(status().isBadRequest());
    }

    private ResultActions create(String key, long amount) throws Exception {
        return mockMvc.perform(post("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"amount_minor\":" + amount + ",\"currency\":\"TRY\",\"callback_url\":\"" + callbackUrl
                        + "\",\"reference\":\"ref\"}"));
    }

    private String createPayment(long amount) throws Exception {
        String body = create("k-" + System.nanoTime(), amount).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        return id(body);
    }

    private ResultActions complete(String id, String body) throws Exception {
        var request = post("/v1/sim/payments/" + id + "/complete").contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(body == null ? request : request.content(body));
    }

    private ResultActions capture(String id, String key) throws Exception {
        return mockMvc.perform(post("/v1/payments/" + id + "/capture").header("Idempotency-Key", key));
    }

    private ResultActions refund(String id, String key, long amount) throws Exception {
        return mockMvc.perform(post("/v1/payments/" + id + "/refunds").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content("{\"amount_minor\":" + amount + "}"));
    }

    private String id(String json) {
        return jsonMapper.readTree(json).path("id").asString();
    }

    private List<Delivery> awaitDeliveries(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (deliveries.size() >= count) {
                return List.copyOf(deliveries);
            }
            Thread.sleep(25);
        }
        throw new AssertionError("expected " + count + " deliveries but got " + deliveries.size());
    }

    private static String expectedSignature(Delivery delivery) {
        try {
            long timestamp = Long.parseLong(delivery.signature().split(",")[0].substring(2));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + delivery.body()).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
