package com.routemesh.surgeservice.topology;

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.processor.PunctuationType;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.routemesh.surgeservice.config.SurgeProperties;
import com.routemesh.surgeservice.publish.SurgePublisher;

/**
 * Bölge başına durumu tutar (kayıt geldikçe yalnızca günceller) ve duvar saatiyle tick'te bir tüm bölgeleri
 * hesaplar: pencere dışını atar, hedef çarpanı bulur, süzgeçten geçirir ve yayınlar. Hesaplamanın olay akışından
 * bağımsız, duvar saatine bağlı olması sessiz kalan bölgede de çarpanın kendiliğinden düşmesini sağlar.
 */
class ZoneSurgeProcessor implements Processor<String, ZoneSignal, Void, Void> {

    static final String STORE = "zone-state";

    private static final Logger log = LoggerFactory.getLogger(ZoneSurgeProcessor.class);

    private final SurgeProperties properties;
    private final SurgePublisher publisher;
    private KeyValueStore<String, ZoneState> store;

    ZoneSurgeProcessor(SurgeProperties properties, SurgePublisher publisher) {
        this.properties = properties;
        this.publisher = publisher;
    }

    @Override
    public void init(ProcessorContext<Void, Void> context) {
        this.store = context.getStateStore(STORE);
        context.schedule(properties.tick(), PunctuationType.WALL_CLOCK_TIME, this::recompute);
    }

    @Override
    public void process(Record<String, ZoneSignal> record) {
        ZoneSignal signal = record.value();
        ZoneState state = store.get(record.key());
        if (state == null) {
            state = new ZoneState();
        }
        switch (signal.type()) {
            case SEEN -> state.idleCouriers.merge(signal.id(), signal.timestamp(), Math::max);
            case GONE -> state.idleCouriers.remove(signal.id());
            case DEMAND -> state.demandByMinute.merge(signal.timestamp() / ZoneState.BUCKET_MILLIS, 1, Integer::sum);
        }
        store.put(record.key(), state);
    }

    private void recompute(long nowMillis) {
        List<KeyValue<String, ZoneState>> zones = new ArrayList<>();
        try (KeyValueIterator<String, ZoneState> all = store.all()) {
            all.forEachRemaining(zones::add);
        }
        for (KeyValue<String, ZoneState> zone : zones) {
            ZoneState state = zone.value;
            state.prune(nowMillis, properties.supplyWindow().toMillis(), properties.demandWindow().toMillis());
            int target = SurgeCalculator.targetBps(state.demand(), state.idleCouriers.size(), properties);
            state.bps = SurgeCalculator.damp(state.bps, target, properties);
            try {
                publisher.publish(zone.key, state.bps);
            } catch (RuntimeException e) {
                log.warn("Could not publish surge for zone {}: {}", zone.key, e.toString());
            }
            if (state.hasNoActivity() && state.bps == SurgeProperties.NO_SURGE_BPS) {
                store.delete(zone.key);
            } else {
                store.put(zone.key, state);
            }
        }
    }
}
