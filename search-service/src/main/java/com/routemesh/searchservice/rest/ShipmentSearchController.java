package com.routemesh.searchservice.rest;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.routemesh.searchservice.rest.ShipmentSearchRepository.Criteria;
import com.routemesh.searchservice.rest.ShipmentSearchRepository.Row;

/**
 * Gönderi liste uçları (CQRS okuma tarafı). Satıcı yalnızca kendi gönderilerini görür ({@code merchant_id == sub},
 * token'dan; başka satıcı istenirse {@code 403}). Eventual consistency: yeni olay birkaç yüz ms gecikmeyle görünür.
 */
@RestController
class ShipmentSearchController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final Set<String> STATUSES = Set.of("PENDING_PAYMENT", "PAID", "AT_WAREHOUSE", "READY_FOR_DISPATCH",
            "ASSIGNED", "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERY_FAILED", "RETURN_TO_WAREHOUSE", "DAMAGED",
            "ON_HOLD", "CANCELLED", "LOST");
    static final List<String> WAREHOUSE_DEFAULT = List.of("PAID", "AT_WAREHOUSE", "READY_FOR_DISPATCH");

    record Money(long amountMinor, String currency) {
    }

    record ShipmentItem(UUID id, UUID merchantId, String status, UUID courierId, Money amount, Instant createdAt,
                        Instant updatedAt, Instant deliveredAt) {

        static ShipmentItem from(Row row) {
            return new ShipmentItem(row.id(), row.merchantId(), row.status(), row.courierId(),
                    new Money(row.amountMinor(), row.currency()), row.createdAt(), row.updatedAt(),
                    row.deliveredAt());
        }
    }

    record ShipmentsResponse(List<ShipmentItem> items, String nextCursor) {
    }

    private final ShipmentSearchRepository shipments;

    ShipmentSearchController(ShipmentSearchRepository shipments) {
        this.shipments = shipments;
    }

    @GetMapping("/api/v1/search/shipments")
    @PreAuthorize("hasRole('MERCHANT')")
    ShipmentsResponse merchantShipments(Authentication authentication,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID courierId, @RequestParam(required = false) UUID merchantId,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {
        UUID self = subject(authentication);
        if (merchantId != null && !merchantId.equals(self)) {
            throw new AccessDeniedException("merchants may only list their own shipments");
        }
        return list(new Criteria(self, courierId, statuses(status), from, to), limit, cursor);
    }

    @GetMapping("/api/v1/ops/shipments")
    @PreAuthorize("hasRole('OPS')")
    ShipmentsResponse opsShipments(@RequestParam(required = false) List<String> status,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID courierId, @RequestParam(required = false) UUID merchantId,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {
        return list(new Criteria(merchantId, courierId, statuses(status), from, to), limit, cursor);
    }

    @GetMapping("/api/v1/warehouse/shipments")
    @PreAuthorize("hasAnyRole('WAREHOUSE', 'OPS')")
    ShipmentsResponse warehouseShipments(@RequestParam(required = false) List<String> status,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {
        List<String> requested = statuses(status);
        return list(new Criteria(null, null, requested == null ? WAREHOUSE_DEFAULT : requested, null, null), limit,
                cursor);
    }

    private ShipmentsResponse list(Criteria criteria, int limit, String cursor) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw bad("limit must be between 1 and " + MAX_LIMIT);
        }
        if (criteria.from() != null && criteria.to() != null && !criteria.from().isBefore(criteria.to())) {
            throw bad("from must be before to");
        }
        ListCursor after = null;
        if (cursor != null) {
            try {
                after = ListCursor.decode(cursor);
            } catch (IllegalArgumentException e) {
                throw bad("cursor is not valid");
            }
        }
        List<Row> rows = shipments.page(criteria, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Row> pageRows = more ? rows.subList(0, limit) : rows;
        String next = more ? new ListCursor(pageRows.getLast().createdAt(), pageRows.getLast().id()).encode() : null;
        return new ShipmentsResponse(pageRows.stream().map(ShipmentItem::from).toList(), next);
    }

    private static List<String> statuses(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : requested) {
            if (!STATUSES.contains(value)) {
                throw bad("unknown status: " + value);
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static UUID subject(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("token subject is not a valid id");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
