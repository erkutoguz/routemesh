package com.routemesh.platform.observability;

import org.springframework.http.server.observation.ServerRequestObservationContext;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;

/**
 * {@code /actuator/**} isteklerini gözlemlemez (ne iz ne HTTP metriği). Sağlık denetimi ve Prometheus taraması
 * saniyeler içinde yüzlerce anlamsız iz üretir ve gerçek isteklerin izlerini gömer; servis sağlığı zaten {@code up}
 * metriğinden ve sağlık ucundan izlenir.
 */
public class ActuatorObservationPredicate implements ObservationPredicate {

    static final String PREFIX = "/actuator";

    @Override
    public boolean test(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext http) {
            String path = http.getCarrier().getRequestURI();
            return !(path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
        }
        return true;
    }
}
