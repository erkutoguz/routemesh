package com.routemesh.notificationservice.handler;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class NotificationRepository {

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meters;

    public NotificationRepository(JdbcClient jdbc, JsonMapper jsonMapper, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.meters = meters;
    }

    public boolean insert(Notification n) {
        boolean inserted = jdbc.sql("INSERT INTO notifications (event_id, template, recipient_type, recipient_id, channel, "
                        + "shipment_id, params) VALUES (:event, :template, :type, :recipient, :channel, :shipment, "
                        + "CAST(:params AS jsonb)) ON CONFLICT ON CONSTRAINT notifications_dedupe_key DO NOTHING")
                .param("event", n.eventId())
                .param("template", n.template())
                .param("type", n.recipientType().name())
                .param("recipient", n.recipientId())
                .param("channel", n.channel().name())
                .param("shipment", n.shipmentId())
                .param("params", jsonMapper.writeValueAsString(n.params()))
                .update() == 1;
        if (inserted) { // tekrar eden olay yeni bildirim sayılmaz; template ve channel sabit kümelerdir
            meters.counter("routemesh.notification.generated", "template", n.template(), "channel",
                    n.channel().name()).increment();
        }
        return inserted;
    }
}
