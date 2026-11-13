package com.routemesh.shipmentservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.routemesh.shipmentservice.TestcontainersConfiguration;
import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.CreateShipmentCommand;
import com.routemesh.shipmentservice.domain.DeliverCommand;
import com.routemesh.shipmentservice.domain.PodFile;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.storage.TestPodStorageConfiguration;

/**
 * {@code deliver} OTP doğrulaması için ikinci bir bağlantı ister (REQUIRES_NEW). Dış işlem bir bağlantıyı tutarken
 * bunu yaparsa, havuzdan fazla eşzamanlı teslimat birbirini bekleyip bağlantı zaman aşımına düşer. Havuz bilerek küçük
 * (3) ve zaman aşımı kısa (4 sn) tutulur; 9 eşzamanlı teslimatın hepsi başarılı olmalıdır.
 */
@SpringBootTest(properties = {
        "spring.grpc.server.port=0",
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.connection-timeout=4000"})
@Import({TestcontainersConfiguration.class, TestPodStorageConfiguration.class})
class DeliveryConnectionPoolTest {

    private static final double LAT = 41.0;
    private static final double LON = 29.0;
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    private static final int DELIVERIES = 9;

    @Autowired
    private ProofOfDeliveryService pod;

    @Autowired
    private ShipmentService shipments;

    @Autowired
    private OtpService otp;

    @Test
    void concurrentDeliveriesWithOtp_completeWithoutExhaustingTheConnectionPool() throws Exception {
        List<Callable<ShipmentStatus>> calls = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < DELIVERIES; i++) {
            UUID courier = UUID.randomUUID();
            UUID id = outForDelivery(courier);
            PodFile photo = pod.upload(id, courier, PodFile.Kind.PHOTO, JPEG);
            String code = otp.issue(id).code();
            DeliverCommand command = new DeliverCommand(id, courier, UUID.randomUUID().toString(), photo.id(), null,
                    LAT, LON, 5.0, "Ada Lovelace", code);
            calls.add(() -> {
                start.await();
                return pod.deliver(command).shipment().status();
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(DELIVERIES);
        try {
            List<Future<ShipmentStatus>> futures = new ArrayList<>();
            calls.forEach(call -> futures.add(executor.submit(call)));
            start.countDown();
            for (Future<ShipmentStatus> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(ShipmentStatus.DELIVERED);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private UUID outForDelivery(UUID courier) {
        UUID id = shipments.create(new CreateShipmentCommand(UUID.randomUUID().toString(), UUID.randomUUID(),
                "Ada Lovelace", "+905551112233", "Moda Cd. 12, Kadikoy", LAT, LON, UUID.randomUUID(), 1001, 800,
                "TRY")).shipment().id();
        move(id, ShipmentStatus.PAID, Actor.SYSTEM, null);
        move(id, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE, null);
        move(id, ShipmentStatus.READY_FOR_DISPATCH, Actor.WAREHOUSE, null);
        move(id, ShipmentStatus.ASSIGNED, Actor.SYSTEM, courier);
        move(id, ShipmentStatus.OUT_FOR_DELIVERY, Actor.COURIER, null);
        return id;
    }

    private void move(UUID id, ShipmentStatus to, Actor actor, UUID courier) {
        shipments.transition(new TransitionCommand(id, to, actor, UUID.randomUUID().toString(), null, courier, null));
    }
}
