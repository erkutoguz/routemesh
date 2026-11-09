package com.routemesh.shipmentservice.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.routemesh.shipmentservice.domain.Shipment;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.service.ShipmentException;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;
import com.routemesh.shipmentservice.service.ShipmentService;

@RestController
@RequestMapping("/api/v1/shipments")
class ShipmentController {

    private final ShipmentService shipmentService;

    ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @GetMapping("/{id}")
    ShipmentResponse get(@PathVariable UUID id, Authentication authentication) {
        CallerActor caller = CallerActor.from(authentication);
        Shipment shipment = shipmentService.get(id);
        if (!caller.canAccess().test(shipment)) {
            throw new ShipmentException(Reason.NOT_FOUND, "shipment " + id + " does not exist");
        }
        return ShipmentResponse.from(shipment);
    }

    @PostMapping("/{id}/transitions")
    ResponseEntity<ShipmentResponse> transition(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody TransitionRequest request, Authentication authentication) {
        CallerActor caller = CallerActor.from(authentication);
        if (request.toStatus() == null) {
            throw new ShipmentException(Reason.INVALID_REQUEST, "to_status is required");
        }
        TransitionCommand command = new TransitionCommand(id, request.toStatus(), caller.actor(), idempotencyKey,
                request.reason(), request.courierId(), request.expectedFromStatus());
        return ResponseEntity.ok(ShipmentResponse.from(
                shipmentService.transition(command, caller.canAccess()).shipment()));
    }
}
