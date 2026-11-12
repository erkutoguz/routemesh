package com.routemesh.surgeservice.topology;

import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.routemesh.surgeservice.zone.ZoneLocator;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * shipment.events içindeki ShipmentCreated olaylarını teslimat noktasının bölgesine talep olarak yazar. Başka
 * olaylar, bozuk kayıtlar, koordinatsız (eski) olaylar ve hizmet bölgesi dışı noktalar atlanır.
 */
class ShipmentDemandProcessor implements Processor<String, String, String, ZoneSignal> {

    private static final Logger log = LoggerFactory.getLogger(ShipmentDemandProcessor.class);

    private final JsonMapper mapper;
    private final ZoneLocator zones;
    private ProcessorContext<String, ZoneSignal> context;

    ShipmentDemandProcessor(JsonMapper mapper, ZoneLocator zones) {
        this.mapper = mapper;
        this.zones = zones;
    }

    @Override
    public void init(ProcessorContext<String, ZoneSignal> context) {
        this.context = context;
    }

    @Override
    public void process(Record<String, String> record) {
        try {
            JsonNode envelope = mapper.readTree(record.value());
            if (!"ShipmentCreated".equals(envelope.path("eventType").asString())) {
                return;
            }
            JsonNode data = envelope.path("data");
            String shipmentId = data.path("shipmentId").asString();
            if (shipmentId.isBlank() || !data.path("deliveryLatitude").isNumber()
                    || !data.path("deliveryLongitude").isNumber()) {
                return;
            }
            zones.locate(data.path("deliveryLatitude").asDouble(), data.path("deliveryLongitude").asDouble())
                    .ifPresent(zone -> context.forward(new Record<>(zone,
                            new ZoneSignal(ZoneSignal.Type.DEMAND, shipmentId, record.timestamp()),
                            record.timestamp())));
        } catch (JacksonException | NullPointerException e) {
            log.debug("Skipping malformed shipment record: {}", e.toString());
        }
    }
}
