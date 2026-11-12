package com.routemesh.pspsimulator;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final SimulatorProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final SimFaults faults;
    private final RestClient restClient = RestClient.create();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);

    public WebhookSender(SimulatorProperties properties, JsonMapper jsonMapper, Clock clock, SimFaults faults) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.faults = faults;
    }

    public String send(String url, String type, Map<String, Object> data, Duration delay, int copies) {
        String eventId = "evt_" + UUID.randomUUID();
        if (faults.dropWebhooks()) {
            log.warn("Dropping webhook {} for {} (fault injection)", type, url);
            return eventId;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", eventId);
        event.put("type", type);
        event.put("created", clock.instant().getEpochSecond());
        event.put("data", data);
        String body = jsonMapper.writeValueAsString(event);
        for (int i = 0; i < Math.max(1, copies); i++) {
            scheduler.schedule(() -> deliver(url, body, 0), delay.toMillis(), TimeUnit.MILLISECONDS);
        }
        return eventId;
    }

    private void deliver(String url, String body, int attempt) {
        boolean delivered = false;
        try {
            String signature = WebhookSigner.header(properties.webhookSecret(), clock.instant().getEpochSecond(), body);
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-PSP-Signature", signature)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            delivered = true;
        } catch (RuntimeException e) {
            log.warn("Webhook delivery to {} failed (attempt {}): {}", url, attempt + 1, e.getMessage());
        }
        List<Duration> delays = properties.retryDelays();
        if (!delivered && attempt < delays.size()) {
            scheduler.schedule(() -> deliver(url, body, attempt + 1), delays.get(attempt).toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
