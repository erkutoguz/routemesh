package com.routemesh.workflowservice.settlement;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Hakediş ve SLA cezası muhasebe şablonları. Saf hesaplamadır (yalnızca {@code long} kuruş); ledger-service jenerik
 * kalır, iş kuralı burada yaşar.
 *
 * <p>Dağılım: satıcı %75 ve kurye %18 aşağı yuvarlanır, kalan (%7 + yuvarlama artığı) platformundur.
 */
public final class SettlementTemplates {

    public static final String ESCROW = "LIABILITY:ESCROW";
    public static final String CLEARING = "ASSET:PSP_CLEARING";
    public static final String PLATFORM_FEE = "REVENUE:PLATFORM_FEE";

    public static final String DELIVERY_SETTLEMENT = "DELIVERY_SETTLEMENT";
    public static final String SLA_PENALTY_REFUND = "SLA_PENALTY_REFUND";

    static final long MERCHANT_PERCENT = 75;
    static final long COURIER_PERCENT = 18;
    /** Her başlayan 15 dakikalık gecikme için gönderi tutarının binde 18'i. */
    static final long PENALTY_PER_STEP_PER_MILLE = 18;
    static final Duration PENALTY_STEP = Duration.ofMinutes(15);

    /** @param debit true: borç, false: alacak */
    public record Line(String account, boolean debit, long amountMinor) {
    }

    /** @param refundToCustomerMinor planla birlikte PSP üzerinden müşteriye iade edilecek tutar (yoksa 0) */
    public record Plan(String transactionType, List<Line> lines, long refundToCustomerMinor) {
    }

    /** Escrow'daki gönderi tutarının nasıl bölündüğü. */
    public record Split(long merchantMinor, long courierMinor, long platformMinor) {
    }

    private SettlementTemplates() {
    }

    public static Split split(long amountMinor) {
        requirePositive(amountMinor);
        long merchant = Math.multiplyExact(amountMinor, MERCHANT_PERCENT) / 100;
        long courier = Math.multiplyExact(amountMinor, COURIER_PERCENT) / 100;
        return new Split(merchant, courier, amountMinor - merchant - courier);
    }

    public static Plan deliverySettlement(UUID merchantId, UUID courierId, long amountMinor) {
        Split split = split(amountMinor);
        return new Plan(DELIVERY_SETTLEMENT, nonZero(
                new Line(ESCROW, true, amountMinor),
                new Line("LIABILITY:MERCHANT_WALLET:" + merchantId, false, split.merchantMinor()),
                new Line("LIABILITY:COURIER_WALLET:" + courierId, false, split.courierMinor()),
                new Line(PLATFORM_FEE, false, split.platformMinor())), 0);
    }

    /** Ledger sıfır tutarlı satırı kabul etmez; çok küçük tutarlarda yuvarlama bir payı sıfırlayabilir. */
    private static List<Line> nonZero(Line... lines) {
        return java.util.Arrays.stream(lines).filter(line -> line.amountMinor() > 0).toList();
    }

    /**
     * Teslim süresi aşıldıysa ceza: her başlayan 15 dakika için {@code floor(tutar × 18 / 1000)}, tavan o
     * gönderideki platform komisyonu. Kurye ve satıcı payına dokunulmaz.
     *
     * @param allowed SLA süresi, {@code actual}: depoya kabulden teslime geçen süre
     */
    public static long slaPenalty(long amountMinor, Duration allowed, Duration actual) {
        requirePositive(amountMinor);
        Duration late = actual.minus(allowed);
        if (late.isNegative() || late.isZero()) {
            return 0;
        }
        long steps = Math.ceilDiv(late.toMillis(), PENALTY_STEP.toMillis());
        long perStep = Math.multiplyExact(amountMinor, PENALTY_PER_STEP_PER_MILLE) / 1000;
        return Math.min(Math.multiplyExact(steps, perStep), split(amountMinor).platformMinor());
    }

    /** Ceza, platformun komisyonundan feragatiyle müşteriye iade edilir. */
    public static Plan slaPenaltyRefund(long penaltyMinor) {
        requirePositive(penaltyMinor);
        return new Plan(SLA_PENALTY_REFUND, List.of(
                new Line(PLATFORM_FEE, true, penaltyMinor),
                new Line(CLEARING, false, penaltyMinor)), penaltyMinor);
    }

    private static void requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
