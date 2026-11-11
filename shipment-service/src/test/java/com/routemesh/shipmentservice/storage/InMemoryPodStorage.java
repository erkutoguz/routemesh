package com.routemesh.shipmentservice.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.routemesh.shipmentservice.service.ShipmentException;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;

/** Servis testleri için bellek içi depo; gerçek S3 yolu {@code S3PodStorageTest}'te sınanır. */
public class InMemoryPodStorage implements PodStorage {

    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    public volatile boolean failing;

    @Override
    public void put(String key, byte[] content, String contentType) {
        if (failing) {
            throw new ShipmentException(Reason.STORAGE_UNAVAILABLE, "proof of delivery storage is unavailable");
        }
        objects.put(key, content.clone());
    }

    @Override
    public InputStream get(String key) {
        byte[] content = objects.get(key);
        if (failing || content == null) {
            throw new ShipmentException(Reason.STORAGE_UNAVAILABLE, "proof of delivery storage is unavailable");
        }
        return new ByteArrayInputStream(content);
    }
}
