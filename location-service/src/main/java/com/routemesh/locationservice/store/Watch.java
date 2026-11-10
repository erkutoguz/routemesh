package com.routemesh.locationservice.store;

/** Bir kuryenin yaklaşmasının izlendiği hedef. {@code kind}: PICKUP (depo) ya da DELIVERY (teslimat noktası). */
public record Watch(String shipmentId, String courierId, String kind, double latitude, double longitude,
                    String merchantId, long version) {

    public static final String PICKUP = "PICKUP";
    public static final String DELIVERY = "DELIVERY";
}
