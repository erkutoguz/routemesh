package com.routemesh.pspsimulator;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Simülatörün bellek içi ödeme deposu (yeniden başlatılınca boşalır, gerçek bir PSP'den farklı olarak). */
@Component
public class SimPaymentStore {

    private final Map<String, SimPayment> payments = new ConcurrentHashMap<>();

    public void put(SimPayment payment) {
        payments.put(payment.id(), payment);
    }

    public SimPayment get(String id) {
        return payments.get(id);
    }

    public Collection<SimPayment> all() {
        return payments.values();
    }
}
