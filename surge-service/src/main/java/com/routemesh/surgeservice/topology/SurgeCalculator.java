package com.routemesh.surgeservice.topology;

import com.routemesh.surgeservice.config.SurgeProperties;

/** Talep ve arzdan çarpan (bps) hesabı ve titreşim önleyici süzgeç. Saf fonksiyonlar, tamsayı aritmetiği. */
public final class SurgeCalculator {

    private static final long RATIO_SCALE_BPS = 5_000;

    private SurgeCalculator() {
    }

    /**
     * {@code oran = talep / max(arz, 1)}, {@code bps = clamp(10000 + floor((oran - 1) × 5000), 10000, maxBps)}.
     * Talep {@code minDemand}'in altındaysa 10000 (1,0×).
     */
    public static int targetBps(long demand, long supply, SurgeProperties properties) {
        if (demand < properties.minDemand()) {
            return SurgeProperties.NO_SURGE_BPS;
        }
        long available = Math.max(supply, 1);
        long raw = SurgeProperties.NO_SURGE_BPS + Math.floorDiv((demand - available) * RATIO_SCALE_BPS, available);
        return (int) Math.min(Math.max(raw, SurgeProperties.NO_SURGE_BPS), properties.maxBps());
    }

    /**
     * Hedefe giden yolu yumuşatır:
     * <ul>
     *   <li>Çıkışta en az {@code minChangeBps} fark varsa hedefe atlanır, daha azsa mevcut değer korunur.</li>
     *   <li>Düşüşte en az {@code minChangeBps} fark varsa adım başına en çok {@code maxDecayStepBps} inilir.</li>
     *   <li>Hedef alt ya da üst sınırsa fark ne olursa olsun oraya varılır (düşüş yine adım adım), böylece değer
     *       sınırın hemen yanında takılı kalmaz.</li>
     * </ul>
     */
    public static int damp(int current, int target, SurgeProperties properties) {
        if (target == current) {
            return current;
        }
        if (target > current) {
            boolean ceiling = target == properties.maxBps();
            return ceiling || target - current >= properties.minChangeBps() ? target : current;
        }
        boolean floor = target == SurgeProperties.NO_SURGE_BPS;
        if (floor || current - target >= properties.minChangeBps()) {
            return Math.max(target, current - properties.maxDecayStepBps());
        }
        return current;
    }
}
