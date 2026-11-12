package com.routemesh.locationservice.tracking;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.routemesh.locationservice.config.SseProperties;
import com.routemesh.locationservice.store.CourierLocationStore;
import com.routemesh.locationservice.store.WatchView;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Satıcının kurye hareketini canlı izlediği SSE akışları.
 * <ul>
 *   <li>Konumlar {@code ingest.lua}'dan Redis Pub/Sub ({@code courier:loc:<kurye>}) ile gelir; her akış yalnızca
 *       kendi kuryesinin kanalına abonedir, son abone ayrılınca abonelik kalkar.</li>
 *   <li>Tek bir zamanlayıcı her {@code tick}'te akışları sanal iş parçacıklarında işler: en fazla
 *       {@code min-interval}'de bir {@code location}, bekleyen {@code approaching}, kalp atışı, süre ve izleme denetimi.
 *       Yavaş bir istemci yalnızca kendi sanal iş parçacığını bekletir.</li>
 *   <li>İzleme biterse ({@code watch:end:<gönderi>}) ya da kaybolursa {@code end} gönderilip akış kapatılır.</li>
 * </ul>
 */
@Service
public class TrackingStreamService implements MessageListener {

    static final String LOCATION_CHANNEL = "courier:loc:";
    static final String END_CHANNEL = "watch:end:";

    private static final Logger log = LoggerFactory.getLogger(TrackingStreamService.class);

    record LocationEvent(double latitude, double longitude, String target, Double distanceMeters, Instant timestamp) {
    }

    record ApproachEvent(String kind, double distanceMeters) {
    }

    record EndEvent(String reason) {
    }

    private final CourierLocationStore store;
    private final RedisMessageListenerContainer container;
    private final SseProperties properties;
    private final JsonMapper jsonMapper;

    private final Map<String, Set<TrackingStream>> byCourier = new ConcurrentHashMap<>();
    private final Map<String, Set<TrackingStream>> byShipment = new ConcurrentHashMap<>();
    private final Map<String, Integer> perUser = new HashMap<>();
    private final AtomicInteger total = new AtomicInteger();

    private ScheduledExecutorService scheduler;
    private ExecutorService workers;

    public TrackingStreamService(CourierLocationStore store, RedisMessageListenerContainer container,
                                 SseProperties properties, JsonMapper jsonMapper, MeterRegistry meters) {
        this.store = store;
        this.container = container;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        Gauge.builder("routemesh.location.sse.connections", total, AtomicInteger::get).register(meters);
    }

    @PostConstruct
    void start() {
        workers = Executors.newVirtualThreadPerTaskExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("tracking-tick").daemon().factory());
        long tick = properties.tick().toMillis();
        scheduler.scheduleWithFixedDelay(this::tick, tick, tick, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        allStreams().forEach(stream -> process(stream, "SHUTDOWN"));
        workers.shutdown();
    }

    int openStreams() {
        return total.get();
    }

    /**
     * @throws TrackingNotFoundException izleme yok ya da gönderi çağıranın değil
     * @throws TooManyStreamsException   kullanıcı ya da sistem sınırı dolu
     */
    public SseEmitter open(String shipmentId, String subject, Instant tokenExpiry) {
        WatchView watch = store.findWatch(shipmentId)
                .filter(found -> subject.equals(found.merchantId()))
                .orElseThrow(TrackingNotFoundException::new);

        long now = System.currentTimeMillis();
        long maxEnd = now + properties.maxDuration().toMillis();
        boolean tokenEndsFirst = tokenExpiry.toEpochMilli() < maxEnd;
        TrackingStream stream = new TrackingStream(watch, subject,
                tokenEndsFirst ? tokenExpiry.toEpochMilli() : maxEnd, tokenEndsFirst ? "TOKEN_EXPIRED" : "TIMEOUT", now);

        register(stream);
        stream.emitter.onCompletion(() -> unregister(stream));
        stream.emitter.onTimeout(() -> unregister(stream));
        stream.emitter.onError(error -> unregister(stream));
        try {
            stream.emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            unregister(stream);
            return stream.emitter;
        }
        // Abone olmadan önce bitmiş olabilir; iznin hâlâ geçerli olduğunu bir kez daha doğrula
        if (store.findWatch(shipmentId).isEmpty()) {
            stream.endReason.compareAndSet(null, "ENDED");
        }
        return stream.emitter;
    }

    private synchronized void register(TrackingStream stream) {
        if (total.get() >= properties.maxTotal()) {
            throw new TooManyStreamsException("too many open tracking streams");
        }
        int mine = perUser.getOrDefault(stream.subject, 0);
        if (mine >= properties.maxPerUser()) {
            throw new TooManyStreamsException("too many open tracking streams for this user");
        }
        perUser.put(stream.subject, mine + 1);
        total.incrementAndGet();
        add(byCourier, stream.courierId, stream, LOCATION_CHANNEL);
        add(byShipment, stream.shipmentId, stream, END_CHANNEL);
    }

    private void add(Map<String, Set<TrackingStream>> index, String key, TrackingStream stream, String channelPrefix) {
        Set<TrackingStream> streams = index.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        if (streams.isEmpty()) {
            container.addMessageListener(this, new ChannelTopic(channelPrefix + key));
        }
        streams.add(stream);
    }

    private synchronized void unregister(TrackingStream stream) {
        if (stream.closed) {
            return;
        }
        stream.closed = true;
        perUser.merge(stream.subject, -1, Integer::sum);
        perUser.remove(stream.subject, 0);
        total.decrementAndGet();
        remove(byCourier, stream.courierId, stream, LOCATION_CHANNEL);
        remove(byShipment, stream.shipmentId, stream, END_CHANNEL);
    }

    private void remove(Map<String, Set<TrackingStream>> index, String key, TrackingStream stream,
                        String channelPrefix) {
        Set<TrackingStream> streams = index.get(key);
        if (streams != null && streams.remove(stream) && streams.isEmpty()) {
            index.remove(key);
            container.removeMessageListener(this, new ChannelTopic(channelPrefix + key));
        }
    }

    // --- Redis Pub/Sub ---

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String body = new String(message.getBody());
        try {
            if (channel.startsWith(LOCATION_CHANNEL)) {
                onLocation(channel.substring(LOCATION_CHANNEL.length()), body);
            } else if (channel.startsWith(END_CHANNEL)) {
                Set<TrackingStream> streams = byShipment.get(channel.substring(END_CHANNEL.length()));
                if (streams != null) {
                    streams.forEach(stream -> stream.endReason.compareAndSet(null, body));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Ignoring malformed tracking message on {}: {}", channel, e.toString());
        }
    }

    private void onLocation(String courierId, String body) {
        Set<TrackingStream> streams = byCourier.get(courierId);
        if (streams == null) {
            return;
        }
        // L|epoch|seq|enlem|boylam  ya da  A|epoch|seq|enlem|boylam|hedef türü|mesafe
        String[] parts = body.split("\\|");
        if (parts[0].equals("L")) {
            TrackingStream.Position position = new TrackingStream.Position(parts[1], parts[2],
                    Double.parseDouble(parts[3]), Double.parseDouble(parts[4]), Instant.now());
            streams.forEach(stream -> stream.pending.set(position));
        } else if (parts[0].equals("A")) {
            TrackingStream.Approach approach = new TrackingStream.Approach(parts[5], Double.parseDouble(parts[6]));
            streams.forEach(stream -> stream.approaches.add(approach));
        }
    }

    // --- zamanlayıcı ---

    private void tick() {
        try {
            for (TrackingStream stream : allStreams()) {
                if (stream.busy.compareAndSet(false, true)) {
                    workers.execute(() -> {
                        try {
                            process(stream, null);
                        } finally {
                            stream.busy.set(false);
                        }
                    });
                }
            }
        } catch (RuntimeException e) {
            log.warn("Tracking tick failed", e);
        }
    }

    private List<TrackingStream> allStreams() {
        List<TrackingStream> all = new ArrayList<>();
        byShipment.values().forEach(all::addAll);
        return all;
    }

    /** @param forcedEnd bu neden verilirse akış hemen kapatılır (kapanış) */
    void process(TrackingStream stream, String forcedEnd) {
        if (stream.closed) {
            return;
        }
        long now = System.currentTimeMillis();
        String end = forcedEnd != null ? forcedEnd : stream.endReason.get();
        if (end == null && now >= stream.deadlineMillis) {
            end = stream.deadlineReason;
        }
        if (end == null && now - stream.lastHealthMillis >= properties.healthInterval().toMillis()) {
            stream.lastHealthMillis = now;
            Optional<WatchView> watch = store.findWatch(stream.shipmentId);
            if (watch.isEmpty()) {
                end = "ENDED";
            } else {
                stream.target = watch.get();
            }
        }
        if (end != null) {
            finish(stream, end);
            return;
        }

        TrackingStream.Approach approach;
        while ((approach = stream.approaches.poll()) != null) {
            if (stream.sentApproaches.add(approach.kind())
                    && !send(stream, SseEmitter.event().name("approaching")
                            .data(jsonMapper.writeValueAsString(new ApproachEvent(approach.kind(), approach.distanceMeters()))))) {
                return;
            }
        }

        TrackingStream.Position position = stream.pending.get();
        if (position != null && now - stream.lastLocationMillis >= properties.minInterval().toMillis()
                && stream.pending.compareAndSet(position, null)) {
            WatchView target = stream.target;
            double distance = Haversine.meters(position.latitude(), position.longitude(),
                    target.latitude(), target.longitude());
            stream.lastLocationMillis = now;
            if (!send(stream, SseEmitter.event().name("location").id(position.epoch() + ":" + position.sequence())
                    .data(jsonMapper.writeValueAsString(new LocationEvent(position.latitude(), position.longitude(),
                            target.kind(), Math.round(distance * 10) / 10.0, position.receivedAt()))))) {
                return;
            }
        }

        if (now - stream.lastWriteMillis >= properties.heartbeat().toMillis()) {
            send(stream, SseEmitter.event().comment("heartbeat"));
        }
    }

    private boolean send(TrackingStream stream, SseEmitter.SseEventBuilder event) {
        try {
            stream.emitter.send(event);
            stream.lastWriteMillis = System.currentTimeMillis();
            return true;
        } catch (IOException | IllegalStateException e) {
            // İstemci koptu ya da akış zaten kapandı
            unregister(stream);
            return false;
        }
    }

    private void finish(TrackingStream stream, String reason) {
        try {
            stream.emitter.send(SseEmitter.event().name("end").data(jsonMapper.writeValueAsString(new EndEvent(reason))));
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not deliver end event for shipment {}: {}", stream.shipmentId, e.toString());
        }
        unregister(stream);
        stream.emitter.complete();
    }
}
