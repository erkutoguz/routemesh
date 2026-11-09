package com.routemesh.pricingservice.grpc;

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

import com.routemesh.contracts.common.LatLong;
import com.routemesh.contracts.pricing.CreateQuoteRequest;
import com.routemesh.contracts.pricing.CreateQuoteResponse;
import com.routemesh.contracts.pricing.GetQuoteRequest;
import com.routemesh.contracts.pricing.PricingServiceGrpc;
import com.routemesh.contracts.pricing.Quote;
import com.routemesh.contracts.pricing.QuoteStatus;
import com.routemesh.contracts.pricing.RedeemQuoteRequest;
import com.routemesh.contracts.pricing.RedeemQuoteResponse;
import com.routemesh.contracts.pricing.ServiceLevel;
import com.routemesh.pricingservice.TestcontainersConfiguration;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
@AutoConfigureTestGrpcTransport
class PricingGrpcServiceTest {

    @Autowired
    private GrpcChannelFactory channels;

    private ManagedChannel channel;
    private PricingServiceGrpc.PricingServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        channel = channels.createChannel("test");
        stub = PricingServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer(TestJwts.token("workflow-sa", "WORKFLOW")));
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
    }

    @Test
    void calls_withoutWorkflowToken_areRejected() {
        PricingServiceGrpc.PricingServiceBlockingStub anonymous = PricingServiceGrpc.newBlockingStub(channel);
        PricingServiceGrpc.PricingServiceBlockingStub expired = PricingServiceGrpc.newBlockingStub(channel)
                .withInterceptors(TestJwts.bearer(TestJwts.token("workflow-sa", -60, "WORKFLOW")));

        assertCode(() -> anonymous.getQuote(GetQuoteRequest.getDefaultInstance()), Status.Code.UNAUTHENTICATED);
        assertCode(() -> expired.getQuote(GetQuoteRequest.getDefaultInstance()), Status.Code.UNAUTHENTICATED);
        for (String role : new String[] {"MERCHANT", "COURIER", "WAREHOUSE", "OPS"}) {
            PricingServiceGrpc.PricingServiceBlockingStub other = PricingServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(TestJwts.bearer(TestJwts.token("someone", role)));
            assertCode(() -> other.getQuote(GetQuoteRequest.getDefaultInstance()), Status.Code.PERMISSION_DENIED);
        }
    }

    @Test
    void createAndGetQuote() {
        CreateQuoteResponse response = stub.createQuote(createRequest(key(), ServiceLevel.SERVICE_LEVEL_EXPRESS));
        Quote fetched = stub.getQuote(GetQuoteRequest.newBuilder().setQuoteId(response.getQuote().getId()).build());

        assertThat(response.getReplayed()).isFalse();
        assertThat(fetched.getStatus()).isEqualTo(QuoteStatus.QUOTE_STATUS_ACTIVE);
        assertThat(fetched.getZoneCode()).isEqualTo("IST_ASIA_CORE");
        assertThat(fetched.getServiceLevel()).isEqualTo(ServiceLevel.SERVICE_LEVEL_EXPRESS);
        assertThat(fetched.getSlaMinutes()).isEqualTo(120);
        assertThat(fetched.getFinalAmount().getCurrency()).isEqualTo("TRY");
        assertThat(fetched.getFinalAmount().getAmountMinor()).isPositive();
        assertThat(fetched.getExpiresAt().getSeconds()).isGreaterThan(fetched.getCreatedAt().getSeconds());
        assertThat(fetched.hasRedeemedShipmentId()).isFalse();
    }

    @Test
    void createQuote_sameRequestTwice_isReplayed() {
        CreateQuoteRequest request = createRequest(key(), ServiceLevel.SERVICE_LEVEL_STANDARD);

        CreateQuoteResponse first = stub.createQuote(request);
        CreateQuoteResponse second = stub.createQuote(request);

        assertThat(second.getReplayed()).isTrue();
        assertThat(second.getQuote().getId()).isEqualTo(first.getQuote().getId());
    }

    @Test
    void createQuote_errors_areMappedToStatusCodes() {
        CreateQuoteRequest valid = createRequest(key(), ServiceLevel.SERVICE_LEVEL_STANDARD);

        assertCode(() -> stub.createQuote(valid.toBuilder().setMerchantId("x").build()), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createQuote(valid.toBuilder().clearOrigin().build()), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createQuote(valid.toBuilder()
                .setServiceLevel(ServiceLevel.SERVICE_LEVEL_UNSPECIFIED).build()), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createQuote(valid.toBuilder().setWeightGrams(0).build()), Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.createQuote(valid.toBuilder().setIdempotencyKey(key())
                .setDestination(LatLong.newBuilder().setLatitude(39.9334).setLongitude(32.8597)).build()),
                Status.Code.FAILED_PRECONDITION);

        String key = key();
        stub.createQuote(createRequest(key, ServiceLevel.SERVICE_LEVEL_STANDARD));
        assertCode(() -> stub.createQuote(createRequest(key, ServiceLevel.SERVICE_LEVEL_EXPRESS)),
                Status.Code.ALREADY_EXISTS);
    }

    @Test
    void redeemQuote_isIdempotentAndSingleUse() {
        String quoteId = stub.createQuote(createRequest(key(), ServiceLevel.SERVICE_LEVEL_STANDARD)).getQuote().getId();
        String shipment = UUID.randomUUID().toString();
        RedeemQuoteRequest request = RedeemQuoteRequest.newBuilder()
                .setQuoteId(quoteId).setRedemptionKey(key()).setShipmentId(shipment).build();

        RedeemQuoteResponse first = stub.redeemQuote(request);
        RedeemQuoteResponse second = stub.redeemQuote(request);

        assertThat(first.getReplayed()).isFalse();
        assertThat(first.getQuote().getStatus()).isEqualTo(QuoteStatus.QUOTE_STATUS_REDEEMED);
        assertThat(first.getQuote().getRedeemedShipmentId()).isEqualTo(shipment);
        assertThat(second.getReplayed()).isTrue();
        assertCode(() -> stub.redeemQuote(request.toBuilder().setRedemptionKey(key()).build()),
                Status.Code.ALREADY_EXISTS);
    }

    @Test
    void redeemAndGet_errors_areMappedToStatusCodes() {
        assertCode(() -> stub.getQuote(GetQuoteRequest.newBuilder().setQuoteId(UUID.randomUUID().toString()).build()),
                Status.Code.NOT_FOUND);
        assertCode(() -> stub.getQuote(GetQuoteRequest.newBuilder().setQuoteId("x").build()),
                Status.Code.INVALID_ARGUMENT);
        assertCode(() -> stub.redeemQuote(RedeemQuoteRequest.newBuilder().setQuoteId(UUID.randomUUID().toString())
                .setRedemptionKey(key()).setShipmentId(UUID.randomUUID().toString()).build()), Status.Code.NOT_FOUND);
        assertCode(() -> stub.redeemQuote(RedeemQuoteRequest.newBuilder().setQuoteId(UUID.randomUUID().toString())
                .setRedemptionKey(key()).setShipmentId("x").build()), Status.Code.INVALID_ARGUMENT);
    }

    private static CreateQuoteRequest createRequest(String key, ServiceLevel level) {
        return CreateQuoteRequest.newBuilder()
                .setIdempotencyKey(key)
                .setMerchantId(UUID.randomUUID().toString())
                .setOrigin(LatLong.newBuilder().setLatitude(41.0136).setLongitude(28.9550))
                .setDestination(LatLong.newBuilder().setLatitude(40.9876).setLongitude(29.0369))
                .setServiceLevel(level)
                .setWeightGrams(2500)
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
