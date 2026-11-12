package com.routemesh.surgeservice.topology;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.routemesh.surgeservice.config.SurgeProperties;

class SurgeCalculatorTest {

    private final SurgeProperties properties = new SurgeProperties("courier.locations", "fleet.events",
            "shipment.events", Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofSeconds(30), 3, 25_000, 500,
            1_000, Duration.ofMinutes(10), 0.005, Duration.ofHours(1), 6);

    // --- hedef çarpan ---

    @ParameterizedTest
    @CsvSource({
            // talep, arz, beklenen bps
            "3,3,10000",    // oran 1: çarpan yok
            "6,3,15000",    // oran 2
            "9,3,20000",    // oran 3
            "12,3,25000",   // oran 4: tavan
            "30,3,25000",   // tavanın üstü kırpılır
            "5,4,11250",    // oran 1,25
            "4,3,11666",    // 1666,67 aşağı yuvarlanır
            "10,20,10000",  // arz fazla: taban
            "3,0,20000",    // arz 0 → 1 sayılır, oran 3
            "100,0,25000"})
    void targetBps_followsTheDocumentedFormula(long demand, long supply, int expected) {
        assertThat(SurgeCalculator.targetBps(demand, supply, properties)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,0", "2,0", "2,1", "2,100"})
    void targetBps_isNeutralBelowTheMinimumDemand(long demand, long supply) {
        assertThat(SurgeCalculator.targetBps(demand, supply, properties)).isEqualTo(10_000);
    }

    @Test
    void targetBps_neverLeavesTheAllowedRange() {
        for (long demand = 0; demand <= 200; demand++) {
            for (long supply = 0; supply <= 200; supply += 7) {
                assertThat(SurgeCalculator.targetBps(demand, supply, properties)).isBetween(10_000, 25_000);
            }
        }
    }

    @Test
    void targetBps_respectsAConfiguredCeiling() {
        SurgeProperties lowCap = new SurgeProperties("a", "b", "c", Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofSeconds(30), 3, 18_000, 500, 1_000, Duration.ofMinutes(10), 0.005, Duration.ofHours(1), 6);

        assertThat(SurgeCalculator.targetBps(100, 1, lowCap)).isEqualTo(18_000);
    }

    // --- süzgeç ---

    @Test
    void damp_ignoresSmallRises_butJumpsOnceTheChangeIsBigEnough() {
        assertThat(SurgeCalculator.damp(10_000, 10_300, properties)).isEqualTo(10_000);
        assertThat(SurgeCalculator.damp(10_000, 10_499, properties)).isEqualTo(10_000);
        assertThat(SurgeCalculator.damp(10_000, 10_500, properties)).isEqualTo(10_500);
        assertThat(SurgeCalculator.damp(12_000, 17_000, properties)).isEqualTo(17_000);
    }

    @Test
    void damp_fallsByAtMostOneStepAtATime() {
        assertThat(SurgeCalculator.damp(15_000, 12_000, properties)).isEqualTo(14_000);
        assertThat(SurgeCalculator.damp(14_000, 12_000, properties)).isEqualTo(13_000);
        assertThat(SurgeCalculator.damp(12_500, 12_000, properties)).isEqualTo(12_000);   // fark 500: kalan kadar iner
        assertThat(SurgeCalculator.damp(25_000, 10_000, properties)).isEqualTo(24_000);
    }

    @Test
    void damp_ignoresSmallFalls() {
        assertThat(SurgeCalculator.damp(15_000, 14_700, properties)).isEqualTo(15_000);
        assertThat(SurgeCalculator.damp(15_000, 14_501, properties)).isEqualTo(15_000);
    }

    @Test
    void damp_reachesTheBoundsEvenWhenTheGapIsSmall() {
        assertThat(SurgeCalculator.damp(10_400, 10_000, properties)).isEqualTo(10_000);   // tabanda takılma yok
        assertThat(SurgeCalculator.damp(10_900, 10_000, properties)).isEqualTo(10_000);
        assertThat(SurgeCalculator.damp(24_800, 25_000, properties)).isEqualTo(25_000);   // tavanda takılma yok
    }

    @Test
    void damp_staysPutWhenTheTargetEqualsTheCurrentValue() {
        assertThat(SurgeCalculator.damp(15_000, 15_000, properties)).isEqualTo(15_000);
    }

    @Test
    void damp_walksDownToTheFloorAndNeverBelow() {
        int bps = 25_000;
        int steps = 0;
        while (bps > 10_000) {
            int next = SurgeCalculator.damp(bps, 10_000, properties);
            assertThat(bps - next).isBetween(1, 1_000);
            bps = next;
            steps++;
        }
        assertThat(steps).isEqualTo(15);
        assertThat(SurgeCalculator.damp(10_000, 10_000, properties)).isEqualTo(10_000);
    }

    @Test
    void damp_alwaysStaysInsideTheRange() {
        for (int current = 10_000; current <= 25_000; current += 250) {
            for (int target = 10_000; target <= 25_000; target += 250) {
                assertThat(SurgeCalculator.damp(current, target, properties)).isBetween(10_000, 25_000);
            }
        }
    }
}
