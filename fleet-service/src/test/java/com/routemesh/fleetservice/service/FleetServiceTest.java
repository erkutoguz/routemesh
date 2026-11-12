package com.routemesh.fleetservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.routemesh.fleetservice.TestcontainersConfiguration;
import com.routemesh.fleetservice.domain.Courier;
import com.routemesh.fleetservice.domain.CourierStatus;
import com.routemesh.fleetservice.domain.CreateOfferCommand;
import com.routemesh.fleetservice.domain.Offer;
import com.routemesh.fleetservice.domain.OfferStatus;
import com.routemesh.fleetservice.repository.FleetRepository;
import com.routemesh.fleetservice.service.FleetException.Reason;

import io.micrometer.core.instrument.MeterRegistry;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.grpc.server.port=0")
class FleetServiceTest {

    @Autowired
    private FleetService service;

    @Autowired
    private FleetRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Test
    void courierIsRegisteredOfflineAndCanToggleIdleAndOffline() {
        UUID id = UUID.randomUUID();

        assertThat(service.findCourier(id)).isEmpty();
        assertThat(service.setStatus(id, CourierStatus.OFFLINE).status()).isEqualTo(CourierStatus.OFFLINE);
        assertThat(service.setStatus(id, CourierStatus.IDLE).status()).isEqualTo(CourierStatus.IDLE);
        assertThat(service.setStatus(id, CourierStatus.IDLE).status()).isEqualTo(CourierStatus.IDLE);
        assertThat(service.setStatus(id, CourierStatus.OFFLINE).status()).isEqualTo(CourierStatus.OFFLINE);
        assertThat(count("outbox_events WHERE aggregate_id = ? AND type = 'CourierStatusChanged'", id.toString()))
                .isEqualTo(2);
        assertReason(() -> service.setStatus(id, CourierStatus.BUSY), Reason.INVALID_REQUEST);
    }

    @Test
    void offerRequiresRegisteredIdleCourierWithEnoughCapacity() {
        UUID shipment = UUID.randomUUID();

        assertReason(() -> offer(UUID.randomUUID(), shipment, 1000, 60), Reason.PRECONDITION_FAILED);

        UUID offline = courier();
        service.setStatus(offline, CourierStatus.OFFLINE);
        assertReason(() -> offer(offline, shipment, 1000, 60), Reason.PRECONDITION_FAILED);

        UUID idle = idleCourier();
        assertReason(() -> offer(idle, shipment, 20_001, 60), Reason.PRECONDITION_FAILED);
        assertThat(offer(idle, shipment, 20_000, 60).offer().status()).isEqualTo(OfferStatus.OFFERED);
    }

    @Test
    void offerInputIsValidated() {
        UUID courier = idleCourier();

        assertReason(() -> offer(courier, UUID.randomUUID(), 0, 60), Reason.INVALID_REQUEST);
        assertReason(() -> offer(courier, UUID.randomUUID(), 1000, 0), Reason.INVALID_REQUEST);
        assertReason(() -> offer(courier, UUID.randomUUID(), 1000, 601), Reason.INVALID_REQUEST);
    }

    @Test
    void sameOfferIdIsReplayedAndConflictsWhenPayloadDiffers() {
        UUID courier = idleCourier();
        UUID shipment = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        CreateOfferCommand command = new CreateOfferCommand(offerId, shipment, courier, 1000, 60);

        var first = service.createOffer(command);
        var second = service.createOffer(command);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.offer().fenceToken()).isEqualTo(first.offer().fenceToken());
        assertReason(() -> service.createOffer(new CreateOfferCommand(offerId, UUID.randomUUID(), courier, 1000, 60)),
                Reason.CONFLICT);
        assertThat(count("assignment_offers WHERE offer_id = ?", offerId)).isEqualTo(1);
    }

    @Test
    void courierWithAnOpenOfferCannotReceiveAnotherUntilItIsDecided() {
        UUID courier = idleCourier();
        Offer first = offer(courier, UUID.randomUUID(), 1000, 60).offer();

        assertReason(() -> offer(courier, UUID.randomUUID(), 1000, 60), Reason.PRECONDITION_FAILED);

        service.reject(courier, first.offerId());
        assertThat(offer(courier, UUID.randomUUID(), 1000, 60).offer().status()).isEqualTo(OfferStatus.OFFERED);
    }

    @Test
    void shipmentCanBeOfferedToOnlyOneCourierAtATime() {
        UUID shipment = UUID.randomUUID();
        UUID first = idleCourier();
        UUID second = idleCourier();
        Offer offered = offer(first, shipment, 1000, 60).offer();

        assertReason(() -> offer(second, shipment, 1000, 60), Reason.PRECONDITION_FAILED);

        service.reject(first, offered.offerId());
        assertThat(offer(second, shipment, 1000, 60).offer().courierId()).isEqualTo(second);
    }

    @Test
    void acceptMakesCourierBusyAndBlocksFurtherOffers() {
        UUID courier = idleCourier();
        UUID shipment = UUID.randomUUID();
        Offer offered = offer(courier, shipment, 1000, 60).offer();

        Offer accepted = service.accept(courier, offered.offerId());

        assertThat(accepted.status()).isEqualTo(OfferStatus.ACCEPTED);
        Courier busy = service.findCourier(courier).orElseThrow();
        assertThat(busy.status()).isEqualTo(CourierStatus.BUSY);
        assertThat(busy.activeShipmentId()).isEqualTo(shipment);
        assertThat(busy.fenceToken()).isGreaterThan(offered.fenceToken());
        assertReason(() -> offer(courier, UUID.randomUUID(), 1000, 60), Reason.PRECONDITION_FAILED);
        assertReason(() -> service.setStatus(courier, CourierStatus.OFFLINE), Reason.PRECONDITION_FAILED);
        assertThat(service.accept(courier, offered.offerId()).status()).isEqualTo(OfferStatus.ACCEPTED);
    }

    @Test
    void courierCannotDecideAnotherCouriersOffer() {
        UUID owner = idleCourier();
        UUID other = idleCourier();
        Offer offered = offer(owner, UUID.randomUUID(), 1000, 60).offer();

        assertReason(() -> service.accept(other, offered.offerId()), Reason.NOT_FOUND);
        assertReason(() -> service.reject(other, offered.offerId()), Reason.NOT_FOUND);
        assertReason(() -> service.accept(owner, UUID.randomUUID()), Reason.NOT_FOUND);
        assertThat(service.getOffer(offered.offerId()).status()).isEqualTo(OfferStatus.OFFERED);
    }

    @Test
    void acceptFailsWhenCourierWentOfflineAndLeavesOfferOpen() {
        UUID courier = idleCourier();
        Offer offered = offer(courier, UUID.randomUUID(), 1000, 60).offer();
        service.setStatus(courier, CourierStatus.OFFLINE);

        assertReason(() -> service.accept(courier, offered.offerId()), Reason.PRECONDITION_FAILED);

        assertThat(service.getOffer(offered.offerId()).status()).isEqualTo(OfferStatus.OFFERED);
        assertThat(service.findCourier(courier).orElseThrow().status()).isEqualTo(CourierStatus.OFFLINE);
    }

    @Test
    void rejectIsIdempotentAndCannotUndoAnAcceptedOffer() {
        UUID courier = idleCourier();
        Offer offered = offer(courier, UUID.randomUUID(), 1000, 60).offer();

        assertThat(service.reject(courier, offered.offerId()).status()).isEqualTo(OfferStatus.REJECTED);
        assertThat(service.reject(courier, offered.offerId()).status()).isEqualTo(OfferStatus.REJECTED);
        assertReason(() -> service.accept(courier, offered.offerId()), Reason.PRECONDITION_FAILED);

        UUID accepting = idleCourier();
        Offer second = offer(accepting, UUID.randomUUID(), 1000, 60).offer();
        service.accept(accepting, second.offerId());
        assertReason(() -> service.reject(accepting, second.offerId()), Reason.PRECONDITION_FAILED);
    }

    @Test
    void everyOfferStatusTransitionIsCountedOncePerStatus() {
        double offered = transitions("OFFERED");
        double accepted = transitions("ACCEPTED");
        double rejected = transitions("REJECTED");
        double released = transitions("RELEASED");
        UUID courier = idleCourier();
        UUID shipment = UUID.randomUUID();
        UUID offerId = UUID.randomUUID();
        CreateOfferCommand command = new CreateOfferCommand(offerId, shipment, courier, 1000, 60);

        service.createOffer(command);
        service.createOffer(command); // tekrar oynatma yeni geçiş sayılmaz
        service.accept(courier, offerId);
        service.accept(courier, offerId);
        UUID other = idleCourier();
        service.reject(other, offer(other, UUID.randomUUID(), 1000, 60).offer().offerId());
        service.releaseCourier(courier, shipment);

        assertThat(transitions("OFFERED") - offered).isEqualTo(2);
        assertThat(transitions("ACCEPTED") - accepted).isEqualTo(1);
        assertThat(transitions("REJECTED") - rejected).isEqualTo(1);
        assertThat(transitions("RELEASED") - released).isEqualTo(1);
    }

    private double transitions(String toStatus) {
        var counter = meters.find("routemesh.fleet.offer.transitions").tag("to_status", toStatus).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void expiredOfferCannotBeAcceptedAndFreesTheCourier() throws Exception {
        UUID courier = idleCourier();
        UUID shipment = UUID.randomUUID();
        Offer offered = offer(courier, shipment, 1000, 1).offer();

        assertThat(service.expireOffer(offered.offerId()).status()).isEqualTo(OfferStatus.OFFERED);
        Thread.sleep(1200);

        assertThat(service.openOffers(courier)).isEmpty();
        assertReason(() -> service.accept(courier, offered.offerId()), Reason.PRECONDITION_FAILED);
        assertThat(service.expireOffer(offered.offerId()).status()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(service.expireOffer(offered.offerId()).status()).isEqualTo(OfferStatus.EXPIRED);
        assertThat(offer(courier, shipment, 1000, 60).offer().status()).isEqualTo(OfferStatus.OFFERED);
    }

    @Test
    void staleOfferIsExpiredLazilyWhenTheCourierGetsANewOffer() throws Exception {
        UUID courier = idleCourier();
        Offer stale = offer(courier, UUID.randomUUID(), 1000, 1).offer();
        Thread.sleep(1200);

        assertThat(offer(courier, UUID.randomUUID(), 1000, 60).offer().status()).isEqualTo(OfferStatus.OFFERED);

        assertThat(service.getOffer(stale.offerId()).status()).isEqualTo(OfferStatus.EXPIRED);
    }

    @Test
    void expireAfterAcceptReturnsAcceptedSoCallerCanContinue() {
        UUID courier = idleCourier();
        Offer offered = offer(courier, UUID.randomUUID(), 1000, 60).offer();
        service.accept(courier, offered.offerId());

        assertThat(service.expireOffer(offered.offerId()).status()).isEqualTo(OfferStatus.ACCEPTED);
    }

    @Test
    void releaseFreesTheCourierOnlyForTheShipmentItHolds() {
        UUID courier = idleCourier();
        UUID shipment = UUID.randomUUID();
        Offer offered = offer(courier, shipment, 1000, 60).offer();
        service.accept(courier, offered.offerId());

        assertThat(service.releaseCourier(courier, UUID.randomUUID())).isFalse();
        assertThat(service.findCourier(courier).orElseThrow().status()).isEqualTo(CourierStatus.BUSY);

        assertThat(service.releaseCourier(courier, shipment)).isTrue();
        assertThat(service.releaseCourier(courier, shipment)).isFalse();
        assertThat(service.releaseCourier(UUID.randomUUID(), shipment)).isFalse();

        Courier idle = service.findCourier(courier).orElseThrow();
        assertThat(idle.status()).isEqualTo(CourierStatus.IDLE);
        assertThat(idle.activeShipmentId()).isNull();
        assertThat(service.getOffer(offered.offerId()).status()).isEqualTo(OfferStatus.RELEASED);
        assertThat(offer(courier, UUID.randomUUID(), 1000, 60).offer().status()).isEqualTo(OfferStatus.OFFERED);
    }

    @Test
    void fenceTokensOnlyMoveForwardAndStaleWritersAreRejected() {
        UUID courier = idleCourier();
        Offer offered = offer(courier, UUID.randomUUID(), 1000, 60).offer();
        long current = service.findCourier(courier).orElseThrow().fenceToken();
        assertThat(current).isEqualTo(offered.fenceToken());

        assertThat(repository.assignCourier(courier, offered.shipmentId(), current)).isZero();
        assertThat(repository.assignCourier(courier, offered.shipmentId(), current - 1)).isZero();
        assertThat(service.findCourier(courier).orElseThrow().status()).isEqualTo(CourierStatus.IDLE);
        assertThat(repository.assignCourier(courier, offered.shipmentId(), current + 1)).isEqualTo(1);
    }

    @Test
    void parallelOffersToOneCourierYieldExactlyOne() throws Exception {
        UUID courier = idleCourier();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID shipment = UUID.randomUUID();
            tasks.add(() -> tryOffer(courier, shipment));
        }

        assertThat(runConcurrently(tasks).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(count("assignment_offers WHERE courier_id = ? AND status = 'OFFERED'", courier)).isEqualTo(1);
    }

    @Test
    void parallelOffersOfOneShipmentToManyCouriersYieldExactlyOne() throws Exception {
        UUID shipment = UUID.randomUUID();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID courier = idleCourier();
            tasks.add(() -> tryOffer(courier, shipment));
        }

        assertThat(runConcurrently(tasks).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(count("assignment_offers WHERE shipment_id = ?", shipment)).isEqualTo(1);
    }

    @Test
    void parallelAcceptsAssignTheCourierExactlyOnce() throws Exception {
        UUID courier = idleCourier();
        UUID shipment = UUID.randomUUID();
        Offer offered = offer(courier, shipment, 1000, 60).offer();
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                try {
                    return service.accept(courier, offered.offerId()).status() == OfferStatus.ACCEPTED;
                } catch (FleetException e) {
                    assertThat(e.getReason()).isEqualTo(Reason.UNAVAILABLE);
                    return false;
                }
            });
        }

        assertThat(runConcurrently(tasks)).contains(true);

        Courier busy = service.findCourier(courier).orElseThrow();
        assertThat(busy.status()).isEqualTo(CourierStatus.BUSY);
        assertThat(busy.activeShipmentId()).isEqualTo(shipment);
        assertThat(count("outbox_events WHERE aggregate_id = ? AND type = 'CourierStatusChanged' "
                + "AND payload #>> '{data,toStatus}' = 'BUSY'", courier.toString())).isEqualTo(1);
    }

    @Test
    void offerAndAssignmentEmitEvents() {
        UUID courier = idleCourier();
        Offer offered = offer(courier, UUID.randomUUID(), 1000, 60).offer();
        service.accept(courier, offered.offerId());

        List<String> payloads = jdbc.queryForList(
                "SELECT payload::text FROM outbox_events WHERE aggregate_id = ? AND type = 'OfferStatusChanged' "
                        + "ORDER BY created_at", String.class, courier.toString());

        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0)).contains("\"toStatus\"").contains("OFFERED");
        assertThat(payloads.get(1)).contains("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT DISTINCT aggregate_type FROM outbox_events WHERE aggregate_id = ?",
                String.class, courier.toString())).isEqualTo("fleet");
    }

    private boolean tryOffer(UUID courier, UUID shipment) {
        try {
            offer(courier, shipment, 1000, 60);
            return true;
        } catch (FleetException e) {
            assertThat(e.getReason()).isIn(Reason.PRECONDITION_FAILED, Reason.UNAVAILABLE);
            return false;
        }
    }

    private UUID courier() {
        return UUID.randomUUID();
    }

    private UUID idleCourier() {
        UUID id = UUID.randomUUID();
        service.setStatus(id, CourierStatus.IDLE);
        return id;
    }

    private com.routemesh.fleetservice.domain.OfferResult offer(UUID courier, UUID shipment, long weight, int ttl) {
        return service.createOffer(new CreateOfferCommand(UUID.randomUUID(), shipment, courier, weight, ttl));
    }

    private int count(String fromAndWhere, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromAndWhere, Integer.class, args);
    }

    private static void assertReason(Runnable action, Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(FleetException.class,
                e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
