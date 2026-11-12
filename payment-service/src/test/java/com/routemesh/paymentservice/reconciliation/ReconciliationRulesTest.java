package com.routemesh.paymentservice.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.routemesh.paymentservice.domain.PaymentIntent;
import com.routemesh.paymentservice.domain.PaymentStatus;
import com.routemesh.paymentservice.psp.PspClient.PspPayment;

class ReconciliationRulesTest {

    private static final Instant NOW = Instant.parse("2030-06-01T12:00:00Z");
    private static final Instant SETTLED = NOW.minus(Duration.ofHours(1));
    private static final Duration GRACE = Duration.ofMinutes(10);

    @ParameterizedTest
    @CsvSource({
            "CREATED, CREATED", "AUTHORIZED, AUTHORIZED", "CAPTURED, CAPTURED", "PARTIALLY_REFUNDED, CAPTURED",
            "REFUNDED, CAPTURED", "VOIDED, VOIDED", "EXPIRED, CREATED", "EXPIRED, DECLINED", "EXPIRED, VOIDED",
            "FAILED, DECLINED", "FAILED, VOIDED"})
    void everyLegitimateStatusPairingProducesNoFinding(PaymentStatus local, String pspStatus) {
        PaymentIntent intent = intent(local, 0, 0, SETTLED);

        assertThat(ReconciliationRules.compare(intent, psp(pspStatus, 0, 0, SETTLED), NOW, GRACE)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED, AUTHORIZED", "CREATED, CAPTURED", "AUTHORIZED, CREATED", "AUTHORIZED, CAPTURED",
            "AUTHORIZED, VOIDED", "CAPTURED, AUTHORIZED", "CAPTURED, VOIDED", "REFUNDED, AUTHORIZED",
            "VOIDED, AUTHORIZED", "VOIDED, CAPTURED", "EXPIRED, AUTHORIZED", "EXPIRED, CAPTURED",
            "FAILED, AUTHORIZED", "FAILED, CAPTURED"})
    void aStatusThePspShouldNotShowIsAStatusMismatch(PaymentStatus local, String pspStatus) {
        PaymentIntent intent = intent(local, 0, 0, SETTLED);

        List<Finding> findings = ReconciliationRules.compare(intent, psp(pspStatus, 0, 0, SETTLED), NOW, GRACE);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(Finding.STATUS_MISMATCH);
            assertThat(finding.severity()).isEqualTo(Finding.CRITICAL);
            assertThat(finding.localStatus()).isEqualTo(local.name());
            assertThat(finding.pspStatus()).isEqualTo(pspStatus);
            assertThat(finding.intentId()).isEqualTo(intent.id());
            assertThat(finding.detail()).containsKey("expectedPspStatuses");
        });
    }

    @Test
    void capturedAndRefundedAmountsAreComparedIndependently() {
        PaymentIntent intent = intent(PaymentStatus.PARTIALLY_REFUNDED, 1000, 300, SETTLED);

        List<Finding> findings = ReconciliationRules.compare(intent, psp("CAPTURED", 900, 500, SETTLED), NOW, GRACE);

        assertThat(findings).extracting(Finding::type)
                .containsExactly(Finding.CAPTURED_AMOUNT_MISMATCH, Finding.REFUNDED_AMOUNT_MISMATCH);
        assertThat(findings.get(0).detail()).containsEntry("differenceMinor", -100L);
        assertThat(findings.get(0).localCapturedMinor()).isEqualTo(1000);
        assertThat(findings.get(0).pspCapturedMinor()).isEqualTo(900);
        assertThat(findings.get(1).detail()).containsEntry("differenceMinor", 200L);
        assertThat(findings.get(1).localRefundedMinor()).isEqualTo(300);
        assertThat(findings.get(1).pspRefundedMinor()).isEqualTo(500);
    }

    @Test
    void aLostCaptureWebhookShowsUpAsBothAStatusAndACapturedAmountMismatch() {
        PaymentIntent intent = intent(PaymentStatus.AUTHORIZED, 0, 0, SETTLED);

        List<Finding> findings = ReconciliationRules.compare(intent, psp("CAPTURED", 1000, 0, SETTLED), NOW, GRACE);

        assertThat(findings).extracting(Finding::type)
                .containsExactly(Finding.STATUS_MISMATCH, Finding.CAPTURED_AMOUNT_MISMATCH);
    }

    @Test
    void aPairIsLeftAloneWhileEitherSideChangedWithinTheGraceWindow() {
        Instant recent = NOW.minus(Duration.ofMinutes(3));
        PaymentIntent mismatchedLocal = intent(PaymentStatus.AUTHORIZED, 0, 0, recent);
        PaymentIntent mismatchedSettledLocal = intent(PaymentStatus.AUTHORIZED, 0, 0, SETTLED);

        assertThat(ReconciliationRules.compare(mismatchedLocal, psp("CAPTURED", 1000, 0, SETTLED), NOW, GRACE))
                .isEmpty();
        assertThat(ReconciliationRules.compare(mismatchedSettledLocal, psp("CAPTURED", 1000, 0, recent), NOW, GRACE))
                .isEmpty();
        assertThat(ReconciliationRules.compare(mismatchedSettledLocal, psp("CAPTURED", 1000, 0, SETTLED), NOW, GRACE))
                .isNotEmpty();
    }

    @Test
    void theGraceBoundaryIsInclusiveOfExactlyTenMinutes() {
        Instant exactly = NOW.minus(GRACE);

        assertThat(ReconciliationRules.compare(intent(PaymentStatus.AUTHORIZED, 0, 0, exactly),
                psp("CAPTURED", 1000, 0, exactly), NOW, GRACE)).isNotEmpty();
        assertThat(ReconciliationRules.compare(intent(PaymentStatus.AUTHORIZED, 0, 0, exactly.plusMillis(1)),
                psp("CAPTURED", 1000, 0, exactly), NOW, GRACE)).isEmpty();
    }

    @Test
    void aPspPaymentWithoutAnIntentIsUnknownUnlessItIsStillSettling() {
        PspPayment old = psp("CAPTURED", 1000, 0, SETTLED);

        assertThat(ReconciliationRules.unknownAtPlatform(old, NOW, GRACE)).hasValueSatisfying(finding -> {
            assertThat(finding.type()).isEqualTo(Finding.UNKNOWN_AT_PLATFORM);
            assertThat(finding.pspPaymentId()).isEqualTo(old.id());
            assertThat(finding.pspStatus()).isEqualTo("CAPTURED");
            assertThat(finding.pspCapturedMinor()).isEqualTo(1000);
            assertThat(finding.intentId()).isNull();
        });
        assertThat(ReconciliationRules.unknownAtPlatform(psp("CAPTURED", 1000, 0, NOW.minusSeconds(30)), NOW, GRACE))
                .isEmpty();
    }

    @Test
    void aMissingPspRecordCarriesTheLocalSideOfTheStory() {
        PaymentIntent intent = intent(PaymentStatus.CAPTURED, 1000, 0, SETTLED);

        Finding finding = ReconciliationRules.missingAtPsp(intent);

        assertThat(finding.type()).isEqualTo(Finding.MISSING_AT_PSP);
        assertThat(finding.severity()).isEqualTo(Finding.CRITICAL);
        assertThat(finding.intentId()).isEqualTo(intent.id());
        assertThat(finding.shipmentId()).isEqualTo(intent.shipmentId());
        assertThat(finding.pspPaymentId()).isEqualTo(intent.pspPaymentId());
        assertThat(finding.localStatus()).isEqualTo("CAPTURED");
        assertThat(finding.localCapturedMinor()).isEqualTo(1000);
        assertThat(finding.pspStatus()).isNull();
    }

    private static PaymentIntent intent(PaymentStatus status, long captured, long refunded, Instant updatedAt) {
        return new PaymentIntent(UUID.randomUUID(), UUID.randomUUID(), 1000, "TRY", status,
                "psp_" + UUID.randomUUID(), "http://checkout", captured, refunded, null, 1, NOW.plusSeconds(900),
                updatedAt, updatedAt);
    }

    private static PspPayment psp(String status, long captured, long refunded, Instant updatedAt) {
        return new PspPayment("psp_" + UUID.randomUUID(), "ref", status, 1000, captured, refunded, updatedAt,
                updatedAt);
    }
}
