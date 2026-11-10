package com.routemesh.locationservice.store;

import java.util.List;
import java.util.Optional;

public interface CourierLocationStore {

    IngestResult ingest(String courierId, long sessionEpoch, long sequenceNumber, double latitude, double longitude);

    List<NearbyCourier> findNearest(double latitude, double longitude, double radiusMeters, int limit);

    long evictStale();

    /** @return true: izleme kuruldu, false: gönderi bitmiş ya da olay eski/tekrar olduğu için yok sayıldı */
    boolean armWatch(Watch watch);

    /**
     * Gönderinin izlemesini siler ve gönderiyi "bitti" olarak işaretler (geç gelen eski olay yeniden kuramaz).
     * {@code reason} (örn. DELIVERED) açık canlı takip akışlarına bitiş nedeni olarak iletilir.
     */
    void clearWatch(String shipmentId, String reason);

    /** @return gönderinin etkin izlemesi, yoksa (hiç kurulmamış ya da bitmiş) boş */
    Optional<WatchView> findWatch(String shipmentId);

    /** Kafka'ya yazılamayan yaklaşma tetiğini geri alır. */
    void rearm(ProximityHit hit);
}
