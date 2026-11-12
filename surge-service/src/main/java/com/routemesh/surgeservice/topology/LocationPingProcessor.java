package com.routemesh.surgeservice.topology;

import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** courier.locations kaydından konumu alır; zamanı Kafka kaydının zamanıdır. Bozuk kayıt atlanır. */
class LocationPingProcessor implements Processor<String, String, String, Ping> {

    private static final Logger log = LoggerFactory.getLogger(LocationPingProcessor.class);

    private final JsonMapper mapper;
    private ProcessorContext<String, Ping> context;

    LocationPingProcessor(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void init(ProcessorContext<String, Ping> context) {
        this.context = context;
    }

    @Override
    public void process(Record<String, String> record) {
        try {
            JsonNode json = mapper.readTree(record.value());
            String courierId = json.path("courierId").asString();
            if (courierId.isBlank() || !json.path("latitude").isNumber() || !json.path("longitude").isNumber()) {
                return;
            }
            context.forward(new Record<>(courierId, new Ping(json.path("latitude").asDouble(),
                    json.path("longitude").asDouble(), record.timestamp()), record.timestamp()));
        } catch (JacksonException | NullPointerException e) {
            log.debug("Skipping malformed location record: {}", e.toString());
        }
    }
}
