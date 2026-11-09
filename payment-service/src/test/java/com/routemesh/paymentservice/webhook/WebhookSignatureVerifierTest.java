package com.routemesh.paymentservice.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.routemesh.paymentservice.config.PaymentProperties;

class WebhookSignatureVerifierTest {

    private static final long NOW = 1_800_000_000L;
    private static final String BODY = "{\"id\":\"evt_1\",\"type\":\"payment.authorized\"}";

    private final WebhookSignatureVerifier verifier = verifierWith("secret-one", "secret-two");

    @Test
    void validSignature_isAccepted() {
        assertThat(verifier.verify(bytes(BODY), header("secret-one", NOW, BODY))).isTrue();
    }

    @Test
    void anyConfiguredSecret_isAccepted_forKeyRotation() {
        assertThat(verifier.verify(bytes(BODY), header("secret-two", NOW, BODY))).isTrue();
    }

    @Test
    void wrongSecret_isRejected() {
        assertThat(verifier.verify(bytes(BODY), header("not-a-secret", NOW, BODY))).isFalse();
    }

    @Test
    void tamperedBody_isRejected() {
        String header = header("secret-one", NOW, BODY);

        assertThat(verifier.verify(bytes(BODY.replace("evt_1", "evt_2")), header)).isFalse();
    }

    @Test
    void tamperedTimestamp_isRejected() {
        String header = header("secret-one", NOW, BODY).replace("t=" + NOW, "t=" + (NOW + 1));

        assertThat(verifier.verify(bytes(BODY), header)).isFalse();
    }

    @Test
    void timestampOutsideTheTolerance_isRejected() {
        assertThat(verifier.verify(bytes(BODY), header("secret-one", NOW - 301, BODY))).isFalse();
        assertThat(verifier.verify(bytes(BODY), header("secret-one", NOW + 301, BODY))).isFalse();
    }

    @Test
    void timestampOnTheToleranceBoundary_isAccepted() {
        assertThat(verifier.verify(bytes(BODY), header("secret-one", NOW - 300, BODY))).isTrue();
        assertThat(verifier.verify(bytes(BODY), header("secret-one", NOW + 300, BODY))).isTrue();
    }

    @Test
    void multipleV1Values_areAcceptedWhenOneMatches() {
        String valid = header("secret-one", NOW, BODY);
        String header = valid + ",v1=" + "00".repeat(32);

        assertThat(verifier.verify(bytes(BODY), header)).isTrue();
    }

    @Test
    void missingOrMalformedHeaders_areRejected() {
        assertThat(verifier.verify(bytes(BODY), null)).isFalse();
        assertThat(verifier.verify(bytes(BODY), "")).isFalse();
        assertThat(verifier.verify(bytes(BODY), "garbage")).isFalse();
        assertThat(verifier.verify(bytes(BODY), "t=abc,v1=00")).isFalse();
        assertThat(verifier.verify(bytes(BODY), "t=" + NOW)).isFalse();
        assertThat(verifier.verify(bytes(BODY), "v1=" + "00".repeat(32))).isFalse();
        assertThat(verifier.verify(bytes(BODY), "t=" + NOW + ",v1=not-hex")).isFalse();
    }

    @Test
    void noConfiguredSecrets_rejectsEverything() {
        WebhookSignatureVerifier none = verifierWith();

        assertThat(none.verify(bytes(BODY), header("secret-one", NOW, BODY))).isFalse();
    }

    @Test
    void signatureCoversTheRawBytes_notAReSerializedForm() {
        String spaced = "{ \"id\" : \"evt_1\" ,\"type\":\"payment.authorized\" }";
        String header = header("secret-one", NOW, spaced);

        assertThat(verifier.verify(bytes(spaced), header)).isTrue();
        assertThat(verifier.verify(bytes(BODY), header)).isFalse();
    }

    private static WebhookSignatureVerifier verifierWith(String... secrets) {
        PaymentProperties properties = new PaymentProperties("http://psp", "http://hook", List.of(secrets),
                Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(1), Duration.ofSeconds(30), 10);
        return new WebhookSignatureVerifier(properties, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String header(String secret, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
