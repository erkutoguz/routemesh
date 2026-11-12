package com.routemesh.pspsimulator;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/** Test ve E2E için hata enjeksiyonu anahtarları. */
@Component
public class SimFaults {

    private final AtomicBoolean dropWebhooks = new AtomicBoolean();

    public boolean dropWebhooks() {
        return dropWebhooks.get();
    }

    public void dropWebhooks(boolean value) {
        dropWebhooks.set(value);
    }
}
