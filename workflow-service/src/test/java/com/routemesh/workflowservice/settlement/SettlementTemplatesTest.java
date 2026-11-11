package com.routemesh.workflowservice.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.routemesh.workflowservice.settlement.SettlementTemplates.Line;
import com.routemesh.workflowservice.settlement.SettlementTemplates.Plan;
import com.routemesh.workflowservice.settlement.SettlementTemplates.Split;

class SettlementTemplatesTest {

    private static final Duration STANDARD = Duration.ofMinutes(240);
    private static final Duration EXPRESS = Duration.ofMinutes(120);

    // --- bölüşüm ---

    @ParameterizedTest
    @CsvSource({"1001,750,180,71", "100,75,18,7", "1,0,0,1", "10,7,1,2", "99,74,17,8", "7500,5625,1350,525",
            "999999999,749999999,179999999,70000001"})
    void split_roundsMerchantAndCourierDownAndGivesTheRemainderToThePlatform(long amount, long merchant, long courier,
            long platform) {
        assertThat(SettlementTemplates.split(amount)).isEqualTo(new Split(merchant, courier, platform));
    }

    @Test
    void split_neverLosesOrCreatesAKurus() {
        for (long amount = 1; amount <= 20_000; amount++) {
            Split split = SettlementTemplates.split(amount);

            assertThat(split.merchantMinor() + split.courierMinor() + split.platformMinor()).isEqualTo(amount);
            assertThat(split.platformMinor()).isGreaterThanOrEqualTo(amount * 7 / 100);
        }
    }

    @Test
    void split_rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> SettlementTemplates.split(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementTemplates.split(-5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deliverySettlement_debitsEscrowAndCreditsTheThreeParties() {
        UUID merchant = UUID.randomUUID();
        UUID courier = UUID.randomUUID();

        Plan plan = SettlementTemplates.deliverySettlement(merchant, courier, 1001);

        assertThat(plan.transactionType()).isEqualTo("DELIVERY_SETTLEMENT");
        assertThat(plan.lines()).containsExactly(
                new Line("LIABILITY:ESCROW", true, 1001),
                new Line("LIABILITY:MERCHANT_WALLET:" + merchant, false, 750),
                new Line("LIABILITY:COURIER_WALLET:" + courier, false, 180),
                new Line("REVENUE:PLATFORM_FEE", false, 71));
        assertThat(plan.refundToCustomerMinor()).isZero();
        assertBalanced(plan);
    }

    @Test
    void deliverySettlement_leavesOutSharesThatRoundDownToZero() {
        Plan plan = SettlementTemplates.deliverySettlement(UUID.randomUUID(), UUID.randomUUID(), 1);

        assertThat(plan.lines()).containsExactly(
                new Line("LIABILITY:ESCROW", true, 1),
                new Line("REVENUE:PLATFORM_FEE", false, 1));
        assertBalanced(plan);
    }

    @Test
    void deliverySettlement_isBalancedForEveryAmount() {
        UUID merchant = UUID.randomUUID();
        UUID courier = UUID.randomUUID();

        for (long amount = 1; amount <= 5_000; amount++) {
            Plan plan = SettlementTemplates.deliverySettlement(merchant, courier, amount);

            assertBalanced(plan);
        }
    }

    // --- SLA cezası ---

    @Test
    void slaPenalty_isZeroWhenOnTimeOrExactlyAtTheLimit() {
        assertThat(SettlementTemplates.slaPenalty(1001, STANDARD, Duration.ofMinutes(10))).isZero();
        assertThat(SettlementTemplates.slaPenalty(1001, STANDARD, STANDARD)).isZero();
        assertThat(SettlementTemplates.slaPenalty(1001, EXPRESS, Duration.ofMinutes(119))).isZero();
    }

    @ParameterizedTest
    @CsvSource({
            // gecikme (sn), beklenen adım
            "1,1", "900,1", "901,2", "1800,2", "1801,3", "2400,3", "2700,3", "2701,4"})
    void slaPenalty_countsEveryStartedFifteenMinutes(long lateSeconds, long steps) {
        long amount = 100_000; // adım başına 1800, tavan 7000'den büyük olmasın diye 4 adıma kadar

        long penalty = SettlementTemplates.slaPenalty(amount, STANDARD, STANDARD.plusSeconds(lateSeconds));

        assertThat(penalty).isEqualTo(Math.min(steps * 1800, 7000));
    }

    @Test
    void slaPenalty_worksOutTheDocumentedExamples() {
        // 1001 kuruş: adım başına floor(1001*18/1000) = 18, platform payı 71
        assertThat(SettlementTemplates.slaPenalty(1001, STANDARD, STANDARD.plusMinutes(40))).isEqualTo(54);   // 3 adım
        assertThat(SettlementTemplates.slaPenalty(1001, STANDARD, STANDARD.plusMinutes(60))).isEqualTo(71);   // 4 adım = 72, tavan 71
        assertThat(SettlementTemplates.slaPenalty(1001, STANDARD, STANDARD.plusMinutes(70))).isEqualTo(71);   // 5 adım = 90, tavan
        assertThat(SettlementTemplates.slaPenalty(1001, EXPRESS, EXPRESS.plusMinutes(15))).isEqualTo(18);
    }

    @Test
    void slaPenalty_isCappedByThePlatformFeeAndNeverTouchesOtherShares() {
        for (long amount : new long[] {1, 10, 99, 1001, 7500, 123_457}) {
            Split split = SettlementTemplates.split(amount);
            long penalty = SettlementTemplates.slaPenalty(amount, STANDARD, STANDARD.plusDays(30));

            assertThat(penalty).isLessThanOrEqualTo(split.platformMinor());
            assertThat(amount - split.merchantMinor() - split.courierMinor() - penalty).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void slaPenalty_isZeroWhenTheStepAmountRoundsDownToNothing() {
        // 10 kuruş: floor(10*18/1000) = 0
        assertThat(SettlementTemplates.slaPenalty(10, STANDARD, STANDARD.plusHours(2))).isZero();
    }

    @Test
    void slaPenaltyRefund_waivesThePlatformFeeBackToTheClearingAccount() {
        Plan plan = SettlementTemplates.slaPenaltyRefund(54);

        assertThat(plan.transactionType()).isEqualTo("SLA_PENALTY_REFUND");
        assertThat(plan.lines()).containsExactly(
                new Line("REVENUE:PLATFORM_FEE", true, 54),
                new Line("ASSET:PSP_CLEARING", false, 54));
        assertThat(plan.refundToCustomerMinor()).isEqualTo(54);
        assertBalanced(plan);
        assertThatThrownBy(() -> SettlementTemplates.slaPenaltyRefund(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertBalanced(Plan plan) {
        long debit = plan.lines().stream().filter(Line::debit).mapToLong(Line::amountMinor).sum();
        long credit = plan.lines().stream().filter(line -> !line.debit()).mapToLong(Line::amountMinor).sum();
        assertThat(debit).isEqualTo(credit);
        plan.lines().forEach(line -> assertThat(line.amountMinor()).isPositive());
    }
}
