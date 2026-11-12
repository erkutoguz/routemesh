package com.routemesh.surgeservice.topology;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import tools.jackson.databind.json.JsonMapper;

/** Akış içi (dahili) konuların ve durum deposunun JSON serileştirmesi. */
public class JsonSerde<T> implements Serde<T> {

    private final JsonMapper mapper;
    private final Class<T> type;

    public JsonSerde(JsonMapper mapper, Class<T> type) {
        this.mapper = mapper;
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> data == null ? null : mapper.writeValueAsBytes(data);
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> bytes == null ? null : mapper.readValue(bytes, type);
    }
}
