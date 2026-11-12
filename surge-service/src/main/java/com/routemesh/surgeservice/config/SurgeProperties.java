package com.routemesh.surgeservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "routemesh.surge")
public record SurgeProperties(
        @DefaultValue("courier.locations") String locationsTopic,
        @DefaultValue("fleet.events") String fleetTopic,
        @DefaultValue("shipment.events") String shipmentTopic,
        // Arz: bu süre içinde konum göndermiş IDLE kuryeler
        @DefaultValue("5m") Duration supplyWindow,
        // Talep: bu süre içinde oluşturulmuş gönderiler
        @DefaultValue("15m") Duration demandWindow,
        // Hesaplama döngüsü (duvar saati)
        @DefaultValue("30s") Duration tick,
        // Talep bunun altındaysa çarpan 1,0 (küçük örneklemde sıçrama olmasın)
        @DefaultValue("3") int minDemand,
        @DefaultValue("25000") int maxBps,
        // Çarpan en az bu kadar değişmedikçe güncellenmez (titreşim önleme)
        @DefaultValue("500") int minChangeBps,
        // Düşüş, hesaplama döngüsü başına en çok bu kadar
        @DefaultValue("1000") int maxDecayStepBps,
        // Redis anahtarının ömrü; servis durursa surge bu sürede kendiliğinden söner
        @DefaultValue("10m") Duration redisTtl,
        // Bölge çözümü önbelleği için koordinat yuvarlama (derece, ~550 m)
        @DefaultValue("0.005") double zoneCellDegrees,
        @DefaultValue("1h") Duration zoneCacheTtl,
        // Kurye durumu ve konum akışlarını aynı bölümlemede buluşturan iç konuların bölüm sayısı
        @DefaultValue("6") int internalPartitions) {

    public static final int NO_SURGE_BPS = 10_000;
}
