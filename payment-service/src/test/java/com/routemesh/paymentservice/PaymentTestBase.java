package com.routemesh.paymentservice;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.routemesh.paymentservice.domain.CommandResult;
import com.routemesh.paymentservice.domain.CreatePaymentCommand;
import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.service.PaymentService;
import com.routemesh.paymentservice.webhook.WebhookService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import({TestcontainersConfiguration.class, TestPspConfiguration.class})
@SpringBootTest(properties = "spring.grpc.server.port=0")
public abstract class PaymentTestBase {

    protected static final String SECRET = "whsec_dev_routemesh";

    @Autowired
    protected PaymentService paymentService;

    @Autowired
    protected WebhookService webhookService;

    @Autowired
    protected FakePspClient psp;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected JsonMapper jsonMapper;

    @BeforeEach
    void resetPsp() {
        psp.reset();
    }

    protected PaymentIntent newIntent() {
        CommandResult created = paymentService.create(new CreatePaymentCommand(key(), UUID.randomUUID(), 1001, "TRY"));
        return created.payment();
    }

    protected PaymentIntent authorizedIntent() {
        PaymentIntent intent = newIntent();
        deliver("payment.authorized", intent, 1001);
        return paymentService.get(intent.id());
    }

    protected PaymentIntent capturedIntent() {
        PaymentIntent intent = authorizedIntent();
        paymentService.capture(new com.routemesh.paymentservice.domain.OperationCommand(intent.id(), key(), 0,
                "TRY", null));
        return paymentService.get(intent.id());
    }

    protected PaymentIntent expiredIntent() {
        PaymentIntent intent = newIntent();
        paymentService.expire(intent.id());
        return paymentService.get(intent.id());
    }

    protected WebhookService.Result deliver(String type, PaymentIntent intent, long amountMinor) {
        String body = eventBody("evt_" + UUID.randomUUID(), type, intent.pspPaymentId(), amountMinor, "TRY", null);
        return deliverRaw(body);
    }

    protected WebhookService.Result deliverRaw(String body) {
        JsonNode event = jsonMapper.readTree(body);
        return webhookService.process(event, body);
    }

    protected static String eventBody(String eventId, String type, String pspPaymentId, long amountMinor,
                                      String currency, Long refundAmount) {
        String refund = refundAmount == null ? "" : ",\"refund_id\":\"re_1\",\"refund_amount_minor\":" + refundAmount;
        return "{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"created\":1,\"data\":{\"payment_id\":\""
                + pspPaymentId + "\",\"reference\":\"r\",\"amount_minor\":" + amountMinor + ",\"currency\":\""
                + currency + "\"" + refund + "}}";
    }

    protected static String signature(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestampSeconds + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    protected List<JsonNode> eventsFor(UUID paymentId) {
        return jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE aggregate_id = ? "
                        + "ORDER BY created_at, id", String.class, paymentId.toString()).stream()
                .map(jsonMapper::readTree)
                .toList();
    }

    protected String statusOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment_intents WHERE id = ?", String.class, paymentId);
    }

    protected static void eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("condition was not met within 10 seconds");
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }
}
