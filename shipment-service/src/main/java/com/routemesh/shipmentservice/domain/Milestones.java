package com.routemesh.shipmentservice.domain;

import java.time.Instant;

/** Gönderinin SLA hesabında kullanılan iki an: depoya ilk kabul ve teslim. Henüz olmadıysa null. */
public record Milestones(Instant warehouseReceivedAt, Instant deliveredAt) {
}
