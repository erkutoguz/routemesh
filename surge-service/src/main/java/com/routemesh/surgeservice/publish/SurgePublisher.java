package com.routemesh.surgeservice.publish;

public interface SurgePublisher {

    /** Bölgenin güncel çarpanını yayınlar; 10000 (çarpan yok) ise bölgenin kaydı kaldırılır. */
    void publish(String zoneCode, int bps);
}
