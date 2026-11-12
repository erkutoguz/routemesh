package com.routemesh.locationservice.kafka;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.routemesh.locationservice.config.TrackingProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kabul edilen konumları {@code courier.locations} topic'ine kayıplı olarak yayınlar. Konum akışı hiçbir zaman
 * beklemez: paket sınırlı bir kuyruğa konur, ayrı bir thread Kafka'ya yazar. Kuyruk doluysa ya da Kafka
 * yazamıyorsa konum atılır ve sayaç artar.
 */
@Component
public class TelemetryPublisher {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPublisher.class);

    record Telemetry(String courierId, long sessionEpoch, long sequenceNumber, double latitude, double longitude,
                     Instant receivedAt) {
    }

    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper jsonMapper;
    private final TrackingProperties properties;
    private final BlockingQueue<Telemetry> queue;
    private final Counter dropped;
    private volatile boolean running;
    private Thread worker;

    public TelemetryPublisher(KafkaTemplate<String, String> kafka, JsonMapper jsonMapper,
                              TrackingProperties properties, MeterRegistry meters) {
        this.kafka = kafka;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.telemetryQueueCapacity());
        this.dropped = Counter.builder("routemesh.location.telemetry.dropped").register(meters);
    }

    @PostConstruct
    void start() {
        if (!properties.telemetryEnabled()) {
            return;
        }
        running = true;
        worker = Thread.ofPlatform().name("telemetry-publisher").daemon().start(this::drain);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /** Hiçbir zaman bloklamaz. */
    public void offer(String courierId, long sessionEpoch, long sequenceNumber, double latitude, double longitude) {
        if (!properties.telemetryEnabled()) {
            return;
        }
        if (!queue.offer(new Telemetry(courierId, sessionEpoch, sequenceNumber, latitude, longitude, Instant.now()))) {
            dropped.increment();
        }
    }

    private void drain() {
        while (running) {
            try {
                Telemetry telemetry = queue.poll(1, TimeUnit.SECONDS);
                if (telemetry != null) {
                    send(telemetry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void send(Telemetry telemetry) {
        try {
            kafka.send(properties.telemetryTopic(), telemetry.courierId(), jsonMapper.writeValueAsString(telemetry))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            dropped.increment();
                        }
                    });
        } catch (RuntimeException e) {
            dropped.increment();
            log.debug("Telemetry dropped: {}", e.toString());
        }
    }
}
