package com.routemesh.shipmentservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import com.routemesh.contracts.events.EventEnvelope;
import com.routemesh.contracts.events.ShipmentCreatedEvent;
import com.routemesh.contracts.events.ShipmentStatusChangedEvent;
import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.HistoryEntry;
import com.routemesh.shipmentservice.domain.Milestones;
import com.routemesh.shipmentservice.domain.CreateShipmentCommand;
import com.routemesh.shipmentservice.domain.Shipment;
import com.routemesh.shipmentservice.domain.ShipmentResult;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.repository.ShipmentRepository;
import com.routemesh.shipmentservice.repository.ShipmentRepository.StoredShipment;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;

import tools.jackson.databind.json.JsonMapper;

@Service
public class ShipmentService {

    private static final String CURRENCY = "TRY";
    private static final String AGGREGATE_TYPE = "shipment";
    private static final String CREATE_TRANSITION_KEY = "create";
    private static final int MAX_KEY_LENGTH = 255;
    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_ADDRESS_LENGTH = 500;
    private static final int MAX_REASON_LENGTH = 500;
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{10,15}$");

    private final ShipmentRepository repository;
    private final JsonMapper jsonMapper;

    public ShipmentService(ShipmentRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public ShipmentResult create(CreateShipmentCommand command) {
        validate(command);
        String requestHash = hash(command);

        StoredShipment existing = repository.findByIdempotencyKey(command.idempotencyKey()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, requestHash);
        }

        Shipment created = insert(command, requestHash);
        if (created == null) {
            return replayOrConflict(repository.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(),
                    requestHash);
        }

        repository.insertHistory(created.id(), CREATE_TRANSITION_KEY, requestHash, null,
                ShipmentStatus.PENDING_PAYMENT, Actor.MERCHANT, null, null, created.version());
        publish(created.id(), ShipmentCreatedEvent.TYPE, ShipmentCreatedEvent.VERSION,
                new ShipmentCreatedEvent(created.id(), created.merchantId(), created.quoteId(),
                        created.finalAmountMinor(), created.basePriceMinor(), created.currency(),
                        created.status().name(), created.deliveryLatitude(), created.deliveryLongitude()));
        return new ShipmentResult(created, false);
    }

    @Transactional
    public ShipmentResult transition(TransitionCommand command) {
        return transition(command, shipment -> true);
    }

    @Transactional
    public ShipmentResult transition(TransitionCommand command, Predicate<Shipment> access) {
        validate(command);

        Shipment current = repository.findByIdForUpdate(command.shipmentId())
                .filter(access)
                .orElseThrow(() -> new ShipmentException(Reason.NOT_FOUND,
                        "shipment " + command.shipmentId() + " does not exist"));
        String requestHash = hash(command);

        String storedHash = repository.findHistoryRequestHash(command.shipmentId(), command.transitionKey())
                .orElse(null);
        if (storedHash != null) {
            if (!storedHash.equals(requestHash)) {
                throw new ShipmentException(Reason.IDEMPOTENCY_CONFLICT,
                        "transition_key was already used with a different request");
            }
            return new ShipmentResult(current, true);
        }

        if (command.expectedFromStatus() != null && command.expectedFromStatus() != current.status()) {
            throw new ShipmentException(Reason.ILLEGAL_TRANSITION,
                    "shipment is " + current.status() + ", expected " + command.expectedFromStatus());
        }

        boolean requiresPod = repository.findTransitionRequiresPod(current.status(), command.toStatus(),
                        command.actor())
                .orElseThrow(() -> repository.transitionExists(current.status(), command.toStatus())
                        ? new ShipmentException(Reason.ILLEGAL_TRANSITION,
                                "actor " + command.actor() + " may not move a shipment from "
                                        + current.status() + " to " + command.toStatus())
                        : new ShipmentException(Reason.ILLEGAL_TRANSITION,
                                "transition from " + current.status() + " to " + command.toStatus()
                                        + " is not allowed"));
        if (requiresPod && !repository.proofOfDeliveryExists(current.id())) {
            throw new ShipmentException(Reason.ILLEGAL_TRANSITION, "proof of delivery is required");
        }

        UUID courierId = resolveCourier(current, command);

        int updated = repository.updateStatus(current.id(), current.status(), command.toStatus(), courierId);
        if (updated != 1) {
            throw new IllegalStateException("status update affected " + updated + " rows");
        }
        long newVersion = current.version() + 1;
        repository.insertHistory(current.id(), command.transitionKey(), requestHash, current.status(),
                command.toStatus(), command.actor(), courierId, command.reason(), newVersion);
        publish(current.id(), ShipmentStatusChangedEvent.TYPE, ShipmentStatusChangedEvent.VERSION,
                new ShipmentStatusChangedEvent(current.id(), current.status().name(), command.toStatus().name(),
                        command.actor().name(), courierId, command.reason(), newVersion));

        return new ShipmentResult(repository.findById(current.id()).orElseThrow(), false);
    }

    @Transactional(readOnly = true)
    public Shipment get(UUID shipmentId) {
        return repository.findById(shipmentId)
                .orElseThrow(() -> new ShipmentException(Reason.NOT_FOUND,
                        "shipment " + shipmentId + " does not exist"));
    }

    @Transactional(readOnly = true)
    public Milestones milestones(UUID shipmentId) {
        return repository.findMilestones(shipmentId);
    }

    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID shipmentId) {
        get(shipmentId);
        return repository.findHistory(shipmentId);
    }

    private UUID resolveCourier(Shipment current, TransitionCommand command) {
        ShipmentStatus to = command.toStatus();
        if (to == ShipmentStatus.ASSIGNED) {
            if (command.courierId() == null) {
                throw invalid("courier_id is required when assigning a courier");
            }
            return command.courierId();
        }
        if (command.courierId() != null) {
            throw invalid("courier_id is only allowed when assigning a courier");
        }
        boolean courierReleased = current.status() == ShipmentStatus.ASSIGNED
                && (to == ShipmentStatus.READY_FOR_DISPATCH || to == ShipmentStatus.CANCELLED);
        return courierReleased ? null : current.courierId();
    }

    private void publish(UUID shipmentId, String type, int version, Object data) {
        UUID eventId = UUID.randomUUID();
        EventEnvelope<Object> envelope = new EventEnvelope<>(eventId, type, version, Instant.now(),
                shipmentId.toString(), data);
        repository.insertOutbox(eventId, AGGREGATE_TYPE, shipmentId.toString(), type,
                jsonMapper.writeValueAsString(envelope));
    }

    private Shipment insert(CreateShipmentCommand command, String requestHash) {
        try {
            return repository.insert(command, requestHash).orElse(null);
        } catch (DuplicateKeyException e) {
            throw new ShipmentException(Reason.IDEMPOTENCY_CONFLICT, "shipment_id already exists");
        }
    }

    private ShipmentResult replayOrConflict(StoredShipment existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ShipmentException(Reason.IDEMPOTENCY_CONFLICT,
                    "idempotency_key was already used with a different request");
        }
        return new ShipmentResult(existing.shipment(), true);
    }

    private void validate(CreateShipmentCommand command) {
        requireKey(command.idempotencyKey(), "idempotency_key");
        requireText(command.recipientName(), "recipient_name", MAX_NAME_LENGTH);
        if (command.recipientPhone() == null || !PHONE.matcher(command.recipientPhone()).matches()) {
            throw invalid("recipient_phone must be 10 to 15 digits, optionally starting with +");
        }
        requireText(command.deliveryAddress(), "delivery_address", MAX_ADDRESS_LENGTH);
        if (!(command.deliveryLatitude() >= -90 && command.deliveryLatitude() <= 90)) {
            throw invalid("delivery_location latitude must be between -90 and 90");
        }
        if (!(command.deliveryLongitude() >= -180 && command.deliveryLongitude() <= 180)) {
            throw invalid("delivery_location longitude must be between -180 and 180");
        }
        if (command.finalAmountMinor() <= 0 || command.basePriceMinor() <= 0) {
            throw invalid("final_amount and base_price must be greater than 0");
        }
        if (!CURRENCY.equals(command.currency())) {
            throw invalid("currency must be " + CURRENCY);
        }
        if (command.weightGrams() != null && (command.weightGrams() <= 0 || command.weightGrams() > Integer.MAX_VALUE)) {
            throw invalid("weight_grams must be between 1 and " + Integer.MAX_VALUE);
        }
    }

    private void validate(TransitionCommand command) {
        requireKey(command.transitionKey(), "transition_key");
        if (command.reason() != null && command.reason().length() > MAX_REASON_LENGTH) {
            throw invalid("reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
    }

    private static void requireKey(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_KEY_LENGTH) {
            throw invalid(field + " must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw invalid(field + " must be 1 to " + maxLength + " characters");
        }
    }

    private static String hash(CreateShipmentCommand c) {
        return sha256(String.join("|", String.valueOf(c.merchantId()), c.recipientName(), c.recipientPhone(),
                c.deliveryAddress(), String.valueOf(c.deliveryLatitude()), String.valueOf(c.deliveryLongitude()),
                String.valueOf(c.quoteId()), String.valueOf(c.finalAmountMinor()),
                String.valueOf(c.basePriceMinor()), c.currency(), String.valueOf(c.shipmentId()),
                String.valueOf(c.weightGrams())));
    }

    private static String hash(TransitionCommand c) {
        return sha256(String.join("|", String.valueOf(c.toStatus()), String.valueOf(c.actor()),
                Objects.toString(c.reason(), ""), String.valueOf(c.courierId()),
                String.valueOf(c.expectedFromStatus())));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ShipmentException invalid(String message) {
        return new ShipmentException(Reason.INVALID_REQUEST, message);
    }
}
