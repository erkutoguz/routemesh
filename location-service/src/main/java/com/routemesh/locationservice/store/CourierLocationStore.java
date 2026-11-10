package com.routemesh.locationservice.store;

import java.util.List;

public interface CourierLocationStore {

    IngestResult ingest(String courierId, long sessionEpoch, long sequenceNumber, double latitude, double longitude);

    List<NearbyCourier> findNearest(double latitude, double longitude, double radiusMeters, int limit);

    long evictStale();

    /** @return true: izleme kuruldu, false: gönderi bitmiş ya da olay eski/tekrar olduğu için yok sayıldı */
    boolean armWatch(Watch watch);

    /** Gönderinin izlemesini siler ve gönderiyi "bitti" olarak işaretler (geç gelen eski olay yeniden kuramaz). */
    void clearWatch(String shipmentId);

    /** Kafka'ya yazılamayan yaklaşma tetiğini geri alır. */
    void rearm(ProximityHit hit);
}
