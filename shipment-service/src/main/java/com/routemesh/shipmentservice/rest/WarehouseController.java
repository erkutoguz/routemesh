package com.routemesh.shipmentservice.rest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.ShipmentResult;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.service.ShipmentException;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;
import com.routemesh.shipmentservice.service.ShipmentService;

/**
 * Depo personelinin kabul (PAID → AT_WAREHOUSE) ve hazır etme (AT_WAREHOUSE → READY_FOR_DISPATCH) uçları.
 * Genel {@code /transitions} ucuyla aynı geçiş tablosu ve idempotency kurallarından geçer; fark, hedef durumun
 * ve beklenen önceki durumun sabit olması ve toplu çalışabilmesidir. DAMAGED ve iade işlemleri genel uçta kalır.
 */
@RestController
@RequestMapping("/api/v1/warehouse/shipments")
@PreAuthorize("hasRole('WAREHOUSE')")
class WarehouseController {

    static final int MAX_BULK_SIZE = 100;
    // "<anahtar>:<uuid>" geçiş anahtarı en çok 255 karakter olabilir (36 + 1 karakter ayrılır)
    private static final int MAX_BULK_KEY_LENGTH = 255 - 37;

    private final ShipmentService shipmentService;

    WarehouseController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @PostMapping("/{id}/receive")
    ShipmentResponse receive(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return single(id, idempotencyKey, ShipmentStatus.PAID, ShipmentStatus.AT_WAREHOUSE);
    }

    @PostMapping("/{id}/ready")
    ShipmentResponse ready(@PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return single(id, idempotencyKey, ShipmentStatus.AT_WAREHOUSE, ShipmentStatus.READY_FOR_DISPATCH);
    }

    @PostMapping("/receive")
    ResponseEntity<BulkTransitionResponse> receiveBulk(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody BulkTransitionRequest request) {
        return ResponseEntity.ok(bulk(request, idempotencyKey, ShipmentStatus.PAID, ShipmentStatus.AT_WAREHOUSE));
    }

    @PostMapping("/ready")
    ResponseEntity<BulkTransitionResponse> readyBulk(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody BulkTransitionRequest request) {
        return ResponseEntity.ok(bulk(request, idempotencyKey, ShipmentStatus.AT_WAREHOUSE,
                ShipmentStatus.READY_FOR_DISPATCH));
    }

    private ShipmentResponse single(UUID id, String key, ShipmentStatus from, ShipmentStatus to) {
        return ShipmentResponse.from(shipmentService.transition(command(id, key, from, to)).shipment());
    }

    /**
     * Her gönderi kendi işleminde ilerler: biri reddedilirse diğerleri yine uygulanır. İstek aynı anahtarla
     * tekrarlanırsa uygulanmış olanlar {@code REPLAYED}, reddedilenler yeniden değerlendirilir.
     */
    private BulkTransitionResponse bulk(BulkTransitionRequest request, String key, ShipmentStatus from,
            ShipmentStatus to) {
        if (key == null || key.isBlank() || key.length() > MAX_BULK_KEY_LENGTH) {
            throw new ShipmentException(Reason.INVALID_REQUEST,
                    "Idempotency-Key must be 1 to " + MAX_BULK_KEY_LENGTH + " characters");
        }
        List<UUID> ids = request == null ? null : request.shipmentIds();
        if (ids == null || ids.isEmpty() || ids.size() > MAX_BULK_SIZE || ids.contains(null)) {
            throw new ShipmentException(Reason.INVALID_REQUEST,
                    "shipmentIds must contain 1 to " + MAX_BULK_SIZE + " shipment ids");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new ShipmentException(Reason.INVALID_REQUEST, "shipmentIds must not contain duplicates");
        }

        List<BulkTransitionResponse.Item> results = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            try {
                ShipmentResult result = shipmentService.transition(command(id, key + ":" + id, from, to));
                results.add(BulkTransitionResponse.Item.applied(id, result.replayed(),
                        result.shipment().status().name()));
            } catch (ShipmentException e) {
                results.add(BulkTransitionResponse.Item.rejected(id, e.getReason().name(), e.getMessage()));
            }
        }
        return BulkTransitionResponse.of(results);
    }

    private static TransitionCommand command(UUID id, String key, ShipmentStatus from, ShipmentStatus to) {
        return new TransitionCommand(id, to, Actor.WAREHOUSE, key, null, null, from);
    }
}
