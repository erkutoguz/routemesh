package com.routemesh.pricingservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.routemesh.pricingservice.domain.RateCard;

class PriceCalculatorTest {

    private static final RateCard CORE = new RateCard("IST_ASIA_CORE", 4500, 600, 5000, 500, 5500);

    @ParameterizedTest
    @CsvSource({"0,1", "1,1", "999,1", "1000,1", "1001,2", "7340,8", "7999,8", "8000,8", "8001,9"})
    void billableKilometers_roundsUpWithAMinimumOfOne(long meters, long km) {
        assertThat(PriceCalculator.billableKilometers(meters)).isEqualTo(km);
    }

    @ParameterizedTest
    @CsvSource({"1000,0", "5000,0", "5001,1", "6000,1", "6001,2", "7200,3", "30000,25"})
    void extraKilograms_countsStartedKilogramsAboveIncludedWeight(int weightGrams, long extraKg) {
        assertThat(PriceCalculator.extraKilograms(weightGrams, 5000)).isEqualTo(extraKg);
    }

    @Test
    void shortLightParcel_isLiftedToTheMinimumFee() {
        PriceCalculator.Price price = PriceCalculator.calculate(CORE, 1000, 1000, 10000, 10000);

        assertThat(price.basePriceMinor()).isEqualTo(5500);
        assertThat(price.finalAmountMinor()).isEqualTo(5500);
    }

    @Test
    void standardPrice_isBaseFeePlusKilometersPlusExtraWeight() {
        PriceCalculator.Price price = PriceCalculator.calculate(CORE, 7340, 5000, 10000, 10000);

        assertThat(price.basePriceMinor()).isEqualTo(4500 + 8 * 600);
        assertThat(price.finalAmountMinor()).isEqualTo(9300);
    }

    @Test
    void heavyParcel_addsStartedExtraKilograms() {
        PriceCalculator.Price price = PriceCalculator.calculate(CORE, 7340, 7200, 10000, 10000);

        assertThat(price.basePriceMinor()).isEqualTo(9300 + 3 * 500);
    }

    @Test
    void expressMultiplier_isAppliedWithFloorRounding() {
        PriceCalculator.Price price = PriceCalculator.calculate(CORE, 7340, 5000, 15000, 10000);

        assertThat(price.basePriceMinor()).isEqualTo(13950);
    }

    @Test
    void surge_isAppliedOnTheBasePriceWithFloorRounding() {
        PriceCalculator.Price price = PriceCalculator.calculate(CORE, 7340, 5000, 15000, 12500);

        assertThat(price.basePriceMinor()).isEqualTo(13950);
        assertThat(price.finalAmountMinor()).isEqualTo(17437);
    }

    @Test
    void floorRounding_dropsTheFractionalKurus() {
        RateCard odd = new RateCard("X", 1001, 0, 5000, 0, 1);

        assertThat(PriceCalculator.calculate(odd, 1000, 1000, 15000, 10000).basePriceMinor()).isEqualTo(1501);
    }

    @Test
    void overflow_isNotSilentlyWrapped() {
        RateCard huge = new RateCard("X", Long.MAX_VALUE, 1, 5000, 0, 1);

        assertThatThrownBy(() -> PriceCalculator.calculate(huge, 5000, 1000, 10000, 10000))
                .isInstanceOf(ArithmeticException.class);
    }
}
