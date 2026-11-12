package com.routemesh.notificationservice.handler;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

public interface EventHandler {

    String eventType();

    boolean relevant(JsonNode data);

    void handle(UUID eventId, JsonNode data);
}
