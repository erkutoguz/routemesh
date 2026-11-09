package com.routemesh.paymentservice.grpc;

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
import com.routemesh.contracts.payment.CapturePaymentRequest;
import com.routemesh.contracts.payment.CreatePaymentIntentRequest;
import com.routemesh.contracts.payment.ExpirePaymentRequest;
import com.routemesh.contracts.payment.GetPaymentRequest;
import com.routemesh.contracts.payment.PaymentCommandResponse;
import com.routemesh.contracts.payment.PaymentIntent;
import com.routemesh.contracts.payment.PaymentServiceGrpc;
import com.routemesh.contracts.payment.PaymentStatus;
import com.routemesh.contracts.payment.RefundPaymentRequest;
import com.routemesh.contracts.payment.VoidPaymentRequest;
import com.routemesh.paymentservice.FakePspClient;
import com.routemesh.paymentservice.FakePspClient.Mode;
import com.routemesh.paymentservice.TestPspConfiguration;
import com.routemesh.paymentservice.TestcontainersConfiguration;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;
import com.routemesh.paymentservice.webhook.WebhookService;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestPspConfiguration.class, TestJwtConfiguration.class})
@AutoConfigureTestGrpcTransport
class PaymentGrpcServiceTest {

    @Autowired
    private GrpcChannelFactory channels;

    @Autowired
    private FakePspClient psp;

    @Autowired
    private WebhookService webhookService;

    @Autowired
    private JsonMapper jsonMapper;

    private ManagedChannel channel;
    private PaymentServiceGrpc.PaymentServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        psp.reset();
        channel = channels.createChannel("test");
        stub = PaymentServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer(TestJwts.token("workflow-sa", "WORKFLOW")));
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
    }

    @Test
    void calls_withoutWorkflowToken_areRejected() {
        PaymentServiceGrpc.PaymentServiceBlockingStub anonymous = PaymentServiceGrpc.newBlockingStub(channel);
        PaymentServiceGrpc.PaymentServiceBlockingStub expired = PaymentServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer(TestJwts.token("workflow-sa", -60, "WORKFLOW")));

        assertCode(() -> anonymous.getPayment(GetPaymentRequest.getDefaultInstance()), Status.Code.UNAUTHENTICATED);
        assertCode(() -> expired.getPayment(GetPaymentRequest.getDefaultInstance()), Status.Code.UNAUTHENTICATED);
        for (String role : new String[] {"MERCHANT", "COURIER", "WAREHOUSE", "OPS"}) {
            PaymentServiceGrpc.PaymentServiceBlockingStub other = PaymentServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(TestJwts.bearer(TestJwts.token("someone", role)));
            assertCode(() -> other.getPayment(GetPaymentRequest.getDefaultInstance()), Status.Code.PERMISSION_DENIED);
        }
    }

    @Test
    void createAndGetPayment() {
        PaymentCommandResponse response = stub.createPaymentIntent(createRequest(key(), 1001));
        PaymentIntent fetched = stub.getPayment(GetPaymentRequest.newBuilder()
                .setPaymentId(response.getPayment().getId()).build());

        assertThat(response.getReplayed()).isFalse();
        assertThat(response.getPending()).isFalse();
        assertThat(fetched.getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_CREATED);
        assertThat(fetched.getAmount().getAmountMinor()).isEqualTo(1001);
        assertThat(fetched.getAmount().getCurrency()).isEqualTo("TRY");
        assertThat(fetched.getRefundedAmount().getAmountMinor()).isZero();
        assertThat(fetched.getCheckoutUrl()).startsWith("https://psp.test/checkout/");
        assertThat(fetched.getExpiresAt().getSeconds()).isGreaterThan(fetched.getCreatedAt().getSeconds());
        assertThat(fetched.hasFailureReason()).isFalse();
    }

    @Test
    void createPayment_sameRequestTwice_isReplayed() {
        CreatePaymentIntentRequest request = createRequest(key(), 700);

        PaymentCommandResponse first = stub.createPaymentIntent(request);
        PaymentCommandResponse second = stub.createPaymentIntent(request);

        assertThat(second.getReplayed()).isTrue();
        assertThat(second.getPayment().getId()).isEqualTo(first.getPayment().getId());
        assertCode(() -> stub.createPaymentIntent(request.toBuilder()
                .setAmount(Money.newBuilder().setAmountMinor(701).setCurrency("TRY")).build()),
                Status.Code.ALREADY_EXISTS);
    }

    @Test
    void fullLifecycle_throughTheGrpcCommands() {
        PaymentIntent created = stub.createPaymentIntent(createRequest(key(), 1001)).getPayment();
        authorize(created);

        PaymentCommandResponse captured = stub.capturePayment(CapturePaymentRequest.newBuilder()
                .setPaymentId(created.getId()).setIdempotencyKey(key()).build());
        assertThat(captured.getPayment().getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_CAPTURED);

        PaymentCommandResponse partial = stub.refundPayment(refundRequest(created.getId(), 400));
        assertThat(partial.getPayment().getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_PARTIALLY_REFUNDED);
        assertThat(partial.getPayment().getRefundedAmount().getAmountMinor()).isEqualTo(400);

        PaymentCommandResponse full = stub.refundPayment(refundRequest(created.getId(), 601));
        assertThat(full.getPayment().getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_REFUNDED);
    }

    @Test
    void voidAndExpire_throughTheGrpcCommands() {
        PaymentIntent authorized = stub.createPaymentIntent(createRequest(key(), 500)).getPayment();
        authorize(authorized);

        PaymentCommandResponse voided = stub.voidPayment(VoidPaymentRequest.newBuilder()
                .setPaymentId(authorized.getId()).setIdempotencyKey(key()).setReason("NO_COURIER").build());
        assertThat(voided.getPayment().getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_VOIDED);
        assertThat(voided.getPayment().getFailureReason()).isEqualTo("NO_COURIER");

        PaymentIntent fresh = stub.createPaymentIntent(createRequest(key(), 500)).getPayment();
        PaymentCommandResponse expired = stub.expirePayment(ExpirePaymentRequest.newBuilder()
                .setPaymentId(fresh.getId()).build());
        assertThat(expired.getPayment().getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_EXPIRED);
        assertThat(stub.expirePayment(ExpirePaymentRequest.newBuilder().setPaymentId(fresh.getId()).build())
                .getReplayed()).isTrue();
    }

    @Test
    void capture_whenThePspIsUnavailable_isReportedAsPending() {
        PaymentIntent created = stub.createPaymentIntent(createRequest(key(), 1001)).getPayment();
        authorize(created);
        psp.mode(Mode.UNAVAILABLE);

        PaymentCommandResponse response = stub.capturePayment(CapturePaymentRequest.newBuilder()
                .setPaymentId(created.getId()).setIdempotencyKey(key()).build());

        assertThat(response.getPending()).isTrue();
        assertThat(response.getPayment().getStatus()).isEqualTo(PaymentStatus.PAYMENT_STATUS_AUTHORIZED);
    }

    @Test
    void errors_areMappedToStatusCodes() {
        PaymentIntent created = stub.createPaymentIntent(createRequest(key(), 1001)).getPayment();
        String missing = UUID.randomUUID().toString();

        assertCode(() -> stub.getPayment(GetPaymentRequest.newBuilder().setPaymentId(missing).build()),
                Status.Code.NOT_FOUND);
        assertCode(() -> stub.getPayment(GetPaymentRequest.newBuilder().setPaymentId("x").build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createPaymentIntent(createRequest(key(), 1001).toBuilder().setShipmentId("x").build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createPaymentIntent(createRequest(key(), 1001).toBuilder().clearAmount().build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.capturePayment(CapturePaymentRequest.newBuilder().setPaymentId(created.getId())
                .setIdempotencyKey(key()).build()), Status.Code.FAILED_PRECONDITION);
        assertCode(() -> stub.refundPayment(refundRequest(created.getId(), 10)), Status.Code.FAILED_PRECONDITION);
        assertCode(() -> stub.refundPayment(RefundPaymentRequest.newBuilder().setPaymentId(created.getId())
                .setIdempotencyKey(key()).build()), Status.Code.INVALID_ARGUMENT);

        psp.mode(Mode.UNAVAILABLE);
        assertCode(() -> stub.createPaymentIntent(createRequest(key(), 5)), Status.Code.UNAVAILABLE);
        psp.mode(Mode.REJECT);
        assertCode(() -> stub.createPaymentIntent(createRequest(key(), 5)), Status.Code.FAILED_PRECONDITION);
    }

    private void authorize(PaymentIntent intent) {
        String pspId = intent.getCheckoutUrl().substring(intent.getCheckoutUrl().lastIndexOf('/') + 1);
        String body = "{\"id\":\"evt_" + UUID.randomUUID() + "\",\"type\":\"payment.authorized\",\"created\":1,"
                + "\"data\":{\"payment_id\":\"" + pspId + "\",\"amount_minor\":" + intent.getAmount().getAmountMinor()
                + ",\"currency\":\"TRY\"}}";
        webhookService.process(jsonMapper.readTree(body), body);
    }

    private static CreatePaymentIntentRequest createRequest(String key, long amount) {
        return CreatePaymentIntentRequest.newBuilder()
                .setIdempotencyKey(key)
                .setShipmentId(UUID.randomUUID().toString())
                .setAmount(Money.newBuilder().setAmountMinor(amount).setCurrency("TRY"))
                .build();
    }

    private static RefundPaymentRequest refundRequest(String paymentId, long amount) {
        return RefundPaymentRequest.newBuilder()
                .setPaymentId(paymentId)
                .setIdempotencyKey(key())
                .setAmount(Money.newBuilder().setAmountMinor(amount).setCurrency("TRY"))
                .setReason("TEST")
                .build();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static void assertCode(Runnable call, Status.Code code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(code));
    }
}
