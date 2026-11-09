package com.routemesh.workflowservice.rest;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.routemesh.workflowservice.workflow.ShipmentWorkflowInput;
import com.routemesh.workflowservice.workflow.ShipmentWorkflowResult;

@RestController
@RequestMapping("/api/v1/shipments")
class ShipmentRequestController {

    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{10,15}$");
    private static final int MAX_KEY_LENGTH = 255;

    private final ShipmentStarter starter;

    ShipmentRequestController(ShipmentStarter starter) {
        this.starter = starter;
    }

    public record CreateShipmentBody(UUID quoteId, String recipientName, String recipientPhone,
            String deliveryAddress) {
    }

    @PostMapping
    @PreAuthorize("hasRole('MERCHANT')")
    ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateShipmentBody body, Authentication authentication) {
        UUID merchantId = merchantId(authentication);
        validate(idempotencyKey, body);
        ShipmentWorkflowInput input = new ShipmentWorkflowInput(
                ShipmentStarter.shipmentIdFor(merchantId, idempotencyKey), merchantId, body.quoteId(),
                body.recipientName(), body.recipientPhone(), body.deliveryAddress());

        ShipmentStarter.Outcome outcome = starter.start(input);
        return switch (outcome) {
            case ShipmentStarter.Outcome.CheckoutReady ready -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "shipmentId", ready.shipmentId(),
                    "paymentId", ready.checkout().paymentId(),
                    "checkoutUrl", ready.checkout().checkoutUrl()));
            case ShipmentStarter.Outcome.Pending pending -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("shipmentId", pending.shipmentId(), "status", "PENDING"));
            case ShipmentStarter.Outcome.Finished finished -> finished(finished);
        };
    }

    private static ResponseEntity<?> finished(ShipmentStarter.Outcome.Finished finished) {
        ShipmentWorkflowResult result = finished.result();
        if (ShipmentWorkflowResult.PAID.equals(result.status())) {
            return ResponseEntity.ok(Map.of("shipmentId", finished.shipmentId(), "status", result.status()));
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "shipment request was cancelled: " + result.reason());
        problem.setProperty("shipmentId", finished.shipmentId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    private static UUID merchantId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "token subject is not a merchant id");
        }
    }

    private static void validate(String idempotencyKey, CreateShipmentBody body) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw bad("Idempotency-Key header is required (1 to " + MAX_KEY_LENGTH + " characters)");
        }
        if (body.quoteId() == null) {
            throw bad("quoteId is required");
        }
        if (body.recipientName() == null || body.recipientName().isBlank() || body.recipientName().length() > 200) {
            throw bad("recipientName must be 1 to 200 characters");
        }
        if (body.recipientPhone() == null || !PHONE.matcher(body.recipientPhone()).matches()) {
            throw bad("recipientPhone must be 10 to 15 digits, optionally starting with +");
        }
        if (body.deliveryAddress() == null || body.deliveryAddress().isBlank()
                || body.deliveryAddress().length() > 500) {
            throw bad("deliveryAddress must be 1 to 500 characters");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
