package com.routemesh.shipmentservice.storage;

import java.io.InputStream;

/** Teslimat kanıtı dosyalarının nesne deposu. Yalnızca ekleme ve okuma var: kanıt değişmezdir. */
public interface PodStorage {

    /**
     * @throws com.routemesh.shipmentservice.service.ShipmentException {@code STORAGE_UNAVAILABLE}
     */
    void put(String key, byte[] content, String contentType);

    /**
     * @return nesnenin içeriği; çağıran kapatır
     * @throws com.routemesh.shipmentservice.service.ShipmentException {@code STORAGE_UNAVAILABLE}
     */
    InputStream get(String key);
}
