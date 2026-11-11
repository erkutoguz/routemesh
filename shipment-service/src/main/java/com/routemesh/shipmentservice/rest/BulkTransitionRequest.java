package com.routemesh.shipmentservice.rest;

import java.util.List;
import java.util.UUID;

record BulkTransitionRequest(List<UUID> shipmentIds) {
}
