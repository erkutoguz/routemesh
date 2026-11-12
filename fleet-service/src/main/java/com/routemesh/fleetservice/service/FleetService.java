package com.routemesh.fleetservice.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.routemesh.contracts.events.CourierStatusChangedEvent;
import com.routemesh.contracts.events.EventEnvelope;
import com.routemesh.contracts.events.OfferStatusChangedEvent;
import com.routemesh.fleetservice.config.FleetProperties;
import com.routemesh.fleetservice.domain.Courier;
import com.routemesh.fleetservice.domain.CourierStatus;
import com.routemesh.fleetservice.domain.CreateOfferCommand;
import com.routemesh.fleetservice.domain.Offer;
import com.routemesh.fleetservice.domain.OfferResult;
import com.routemesh.fleetservice.domain.OfferStatus;
import com.routemesh.fleetservice.redis.CourierLock;
import com.routemesh.fleetservice.redis.FenceTokens;
import com.routemesh.fleetservice.repository.FleetRepository;
import com.routemesh.fleetservice.repository.FleetRepository.Expiry;
import com.routemesh.fleetservice.service.FleetException.Reason;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kurye durumu ve atama teklifleri. Kurye satırını değiştiren her işlem sırasıyla Redis kuryesi kilidi,
 * veritabanı satır kilidi ve fencing token'lı koşullu UPDATE ile korunur.
 */
@Service
public class FleetService {

    private static final String AGGREGATE_TYPE = "fleet";
    private static final int MIN_OFFER_TTL_SECONDS = 1;

    private final FleetRepository repository;
    private final CourierLock lock;
    private final FenceTokens fenceTokens;
    private final TransactionTemplate tx;
    private final JsonMapper jsonMapper;
    private final FleetProperties properties;
    private final MeterRegistry meters;

    public FleetService(FleetRepository repository, CourierLock lock, FenceTokens fenceTokens,
                        TransactionTemplate tx, JsonMapper jsonMapper, FleetProperties properties,
                        MeterRegistry meters) {
        this.meters = meters;
        this.repository = repository;
        this.lock = lock;
        this.fenceTokens = fenceTokens;
        this.tx = tx;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    public OfferResult createOffer(CreateOfferCommand command) {
        validate(command);
        Offer existing = repository.findOffer(command.offerId()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, command);
        }
        return lock.withLock(command.courierId(), () -> tx.execute(status -> doCreateOffer(command)));
    }

    public Offer getOffer(UUID offerId) {
        return repository.findOffer(offerId).orElseThrow(() -> offerNotFound(offerId));
    }

    /** Süresi dolmuş bir teklifi kapatır. Kabul edilmişse ya da süresi dolmamışsa teklif olduğu gibi döner. */
    public Offer expireOffer(UUID offerId) {
        Offer current = getOffer(offerId);
        if (current.status() != OfferStatus.OFFERED) {
            return current;
        }
        return tx.execute(status -> {
            Optional<Offer> expired = repository.transitionOffer(offerId, OfferStatus.OFFERED,
                    OfferStatus.EXPIRED, Expiry.EXPIRED);
            if (expired.isPresent()) {
                publishOffer(expired.get(), OfferStatus.OFFERED);
                return expired.get();
            }
            return getOffer(offerId);
        });
    }

    /** Kurye hâlâ bu gönderiyi tutuyorsa serbest bırakır; aksi halde dokunmaz (idempotent). */
    public boolean releaseCourier(UUID courierId, UUID shipmentId) {
        return lock.withLock(courierId, () -> tx.execute(status -> {
            Courier courier = repository.findCourierForUpdate(courierId).orElse(null);
            if (courier == null || !shipmentId.equals(courier.activeShipmentId())) {
                return false;
            }
            long token = fenceTokens.next(courierId, courier.fenceToken());
            if (repository.releaseCourier(courierId, shipmentId, token) != 1) {
                throw new FleetException(Reason.PRECONDITION_FAILED, "stale fencing token for courier " + courierId);
            }
            repository.releaseAcceptedOffer(courierId, shipmentId)
                    .ifPresent(offer -> publishOffer(offer, OfferStatus.ACCEPTED));
            publishCourier(courierId, CourierStatus.BUSY, CourierStatus.IDLE, null);
            return true;
        }));
    }

    /**
     * Gönderi terminal duruma geçtiğinde onu tutan kuryeyi bırakır. Kurye kimliği olaydan gelmez (ASSIGNED -> CANCELLED'da
     * gönderideki kurye temizlenir), bu yüzden filo tarafındaki {@code active_shipment_id} kaynak alınır. Tutan kurye
     * yoksa ya da zaten bırakılmışsa {@code false} döner (idempotent); asıl bırakma kilitli ve koşullu
     * {@link #releaseCourier} ile yapılır.
     */
    public boolean releaseCourierOfShipment(UUID shipmentId) {
        return repository.findCourierIdByActiveShipment(shipmentId)
                .map(courierId -> releaseCourier(courierId, shipmentId))
                .orElse(false);
    }

    public Courier setStatus(UUID courierId, CourierStatus target) {
        if (target == CourierStatus.BUSY) {
            throw invalid("status can only be set to IDLE or OFFLINE");
        }
        return tx.execute(status -> {
            repository.ensureCourier(courierId, properties.defaultMaxWeightGrams());
            Courier current = repository.findCourierForUpdate(courierId).orElseThrow();
            if (current.status() == target) {
                return current;
            }
            if (current.status() == CourierStatus.BUSY) {
                throw new FleetException(Reason.PRECONDITION_FAILED,
                        "courier is delivering a shipment and cannot change status");
            }
            repository.updateCourierStatus(courierId, current.status(), target);
            publishCourier(courierId, current.status(), target, null);
            return repository.findCourier(courierId).orElseThrow();
        });
    }

    public Optional<Courier> findCourier(UUID courierId) {
        return repository.findCourier(courierId);
    }

    public List<Offer> openOffers(UUID courierId) {
        return repository.findOpenOffers(courierId);
    }

    public Offer accept(UUID courierId, UUID offerId) {
        return lock.withLock(courierId, () -> tx.execute(status -> {
            Courier courier = repository.findCourierForUpdate(courierId).orElseThrow(() -> offerNotFound(offerId));
            Offer offer = ownedOffer(courierId, offerId);
            if (offer.status() == OfferStatus.ACCEPTED) {
                return offer;
            }
            Offer accepted = repository.transitionOffer(offerId, OfferStatus.OFFERED, OfferStatus.ACCEPTED,
                            Expiry.NOT_EXPIRED)
                    .orElseThrow(() -> new FleetException(Reason.PRECONDITION_FAILED,
                            offer.status() == OfferStatus.OFFERED ? "offer has expired"
                                    : "offer is " + offer.status()));
            if (courier.status() != CourierStatus.IDLE) {
                throw new FleetException(Reason.PRECONDITION_FAILED, "courier is " + courier.status());
            }
            long token = fenceTokens.next(courierId, courier.fenceToken());
            if (repository.assignCourier(courierId, accepted.shipmentId(), token) != 1) {
                throw new FleetException(Reason.PRECONDITION_FAILED, "stale fencing token for courier " + courierId);
            }
            publishOffer(accepted, OfferStatus.OFFERED);
            publishCourier(courierId, CourierStatus.IDLE, CourierStatus.BUSY, accepted.shipmentId());
            return accepted;
        }));
    }

    public Offer reject(UUID courierId, UUID offerId) {
        return tx.execute(status -> {
            Offer offer = ownedOffer(courierId, offerId);
            if (offer.status() == OfferStatus.REJECTED) {
                return offer;
            }
            Offer rejected = repository.transitionOffer(offerId, OfferStatus.OFFERED, OfferStatus.REJECTED,
                            Expiry.ANY)
                    .orElseThrow(() -> new FleetException(Reason.PRECONDITION_FAILED, "offer is " + offer.status()));
            publishOffer(rejected, OfferStatus.OFFERED);
            return rejected;
        });
    }

    private OfferResult doCreateOffer(CreateOfferCommand command) {
        Courier courier = repository.findCourierForUpdate(command.courierId())
                .orElseThrow(() -> new FleetException(Reason.PRECONDITION_FAILED,
                        "courier " + command.courierId() + " is not registered"));
        Offer existing = repository.findOffer(command.offerId()).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, command);
        }
        for (Offer stale : repository.expireStaleOffers(command.courierId())) {
            publishOffer(stale, OfferStatus.OFFERED);
        }
        if (courier.status() != CourierStatus.IDLE) {
            throw new FleetException(Reason.PRECONDITION_FAILED, "courier is " + courier.status());
        }
        if (command.weightGrams() > courier.maxWeightGrams()) {
            throw new FleetException(Reason.PRECONDITION_FAILED, "shipment weighs " + command.weightGrams()
                    + " g but the courier carries at most " + courier.maxWeightGrams() + " g");
        }
        long token = fenceTokens.next(command.courierId(), courier.fenceToken());
        Offer offer;
        try {
            offer = repository.insertOffer(command.offerId(), command.shipmentId(), command.courierId(),
                    (int) command.weightGrams(), token, command.ttlSeconds());
        } catch (DuplicateKeyException e) {
            throw new FleetException(Reason.PRECONDITION_FAILED,
                    "courier already has an open offer or the shipment already has a live offer");
        }
        if (repository.bumpFence(command.courierId(), token) != 1) {
            throw new FleetException(Reason.PRECONDITION_FAILED,
                    "stale fencing token for courier " + command.courierId());
        }
        publishOffer(offer, null);
        return new OfferResult(offer, false);
    }

    private Offer ownedOffer(UUID courierId, UUID offerId) {
        return repository.findOffer(offerId)
                .filter(offer -> offer.courierId().equals(courierId))
                .orElseThrow(() -> offerNotFound(offerId));
    }

    private OfferResult replayOrConflict(Offer existing, CreateOfferCommand command) {
        if (!existing.shipmentId().equals(command.shipmentId()) || !existing.courierId().equals(command.courierId())
                || existing.weightGrams() != command.weightGrams()) {
            throw new FleetException(Reason.CONFLICT, "offer_id was already used with a different request");
        }
        return new OfferResult(existing, true);
    }

    private void publishOffer(Offer offer, OfferStatus from) {
        // to_status OfferStatus sabit kümesidir
        meters.counter("routemesh.fleet.offer.transitions", "to_status", offer.status().name()).increment();
        publish(offer.courierId(), OfferStatusChangedEvent.TYPE, OfferStatusChangedEvent.VERSION,
                new OfferStatusChangedEvent(offer.offerId(), offer.shipmentId(), offer.courierId(),
                        from == null ? null : from.name(), offer.status().name(), offer.expiresAt()));
    }

    private void publishCourier(UUID courierId, CourierStatus from, CourierStatus to, UUID activeShipmentId) {
        publish(courierId, CourierStatusChangedEvent.TYPE, CourierStatusChangedEvent.VERSION,
                new CourierStatusChangedEvent(courierId, from.name(), to.name(), activeShipmentId));
    }

    private void publish(UUID courierId, String type, int version, Object data) {
        UUID eventId = UUID.randomUUID();
        EventEnvelope<Object> envelope = new EventEnvelope<>(eventId, type, version, Instant.now(),
                courierId.toString(), data);
        repository.insertOutbox(eventId, AGGREGATE_TYPE, courierId.toString(), type,
                jsonMapper.writeValueAsString(envelope));
    }

    private void validate(CreateOfferCommand command) {
        if (command.weightGrams() <= 0 || command.weightGrams() > Integer.MAX_VALUE) {
            throw invalid("weight_grams must be between 1 and " + Integer.MAX_VALUE);
        }
        long maxTtl = properties.maxOfferTtl().toSeconds();
        if (command.ttlSeconds() < MIN_OFFER_TTL_SECONDS || command.ttlSeconds() > maxTtl) {
            throw invalid("ttl_seconds must be between " + MIN_OFFER_TTL_SECONDS + " and " + maxTtl);
        }
    }

    private static FleetException offerNotFound(UUID offerId) {
        return new FleetException(Reason.NOT_FOUND, "offer " + offerId + " does not exist");
    }

    private static FleetException invalid(String message) {
        return new FleetException(Reason.INVALID_REQUEST, message);
    }
}
