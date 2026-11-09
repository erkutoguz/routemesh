package com.routemesh.shipmentservice.rest;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.routemesh.shipmentservice.domain.HistoryEntry;
import com.routemesh.shipmentservice.service.ShipmentService;

@RestController
@RequestMapping("/api/v1/ops/shipments")
class OpsShipmentController {

    private final ShipmentService shipmentService;

    OpsShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasRole('OPS')")
    List<HistoryEntry> history(@PathVariable UUID id) {
        return shipmentService.history(id);
    }
}
