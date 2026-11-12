package com.routemesh.shipmentservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.routemesh.shipmentservice.TestcontainersConfiguration;
import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.CreateShipmentCommand;
import com.routemesh.shipmentservice.domain.DeliverCommand;
import com.routemesh.shipmentservice.domain.PodFile;
import com.routemesh.shipmentservice.domain.ShipmentResult;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;
import com.routemesh.shipmentservice.storage.InMemoryPodStorage;
import com.routemesh.shipmentservice.storage.TestPodStorageConfiguration;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(properties = "spring.grpc.server.port=0")
@Import({TestcontainersConfiguration.class, TestPodStorageConfiguration.class})
class ProofOfDeliveryServiceTest {

    // Teslimat konumu 41.0 / 29.0. 0,00044° ≈ 48,9 m (içeride), 0,00046° ≈ 51,1 m (dışarıda)
    private static final double LAT = 41.0;
    private static final double LON = 29.0;
    private static final double INSIDE = LAT + 0.00044;
    private static final double OUTSIDE = LAT + 0.00046;

    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3);
    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3);

    @Autowired
    private ProofOfDeliveryService pod;

    @Autowired
    private ShipmentService shipments;

    @Autowired
    private OtpService otp;

    @Autowired
    private InMemoryPodStorage storage;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private UUID courier;
    private UUID shipment;

    @BeforeEach
    void setUp() {
        storage.failing = false;
        courier = UUID.randomUUID();
        shipment = outForDelivery(courier);
    }

    // --- 50 m kuralı ---

    @Test
    void deliverWithinFiftyMeters_recordsTheProofAndDeliversInOneStep() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        PodFile signature = pod.upload(shipment, courier, PodFile.Kind.SIGNATURE, PNG);

        ShipmentResult result = pod.deliver(command(shipment, key(), photo.id(), signature.id(), INSIDE, null));

        assertThat(result.replayed()).isFalse();
        assertThat(result.shipment().status()).isEqualTo(ShipmentStatus.DELIVERED);
        var proof = pod.getProof(shipment, s -> true);
        assertThat(proof.distanceMeters()).isBetween(48.0, 50.0);
        assertThat(proof.courierId()).isEqualTo(courier);
        assertThat(proof.photoFileId()).isEqualTo(photo.id());
        assertThat(proof.signatureFileId()).isEqualTo(signature.id());
        assertThat(proof.otpVerified()).isFalse();
        assertThat(proof.recipientName()).isEqualTo("Ada Lovelace");
        assertThat(shipments.history(shipment)).last().satisfies(entry -> {
            assertThat(entry.actor()).isEqualTo("COURIER");
            assertThat(entry.toStatus()).isEqualTo("DELIVERED");
        });
    }

    @Test
    void deliverAtTheExactLocation_isAccepted() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);

        pod.deliver(command(shipment, key(), photo.id(), null, LAT, null));

        assertThat(pod.getProof(shipment, s -> true).distanceMeters()).isLessThan(0.5);
    }

    @Test
    void deliverBeyondFiftyMeters_isRejectedWithTheDistance_andNothingChanges() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);

        assertThatThrownBy(() -> pod.deliver(command(shipment, key(), photo.id(), null, OUTSIDE, null)))
                .isInstanceOfSatisfying(PodTooFarException.class, e -> {
                    assertThat(e.getReason()).isEqualTo(Reason.POD_TOO_FAR);
                    assertThat(e.getDistanceMeters()).isBetween(50.0, 52.5);
                });

        assertThat(shipments.get(shipment).status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(count("proof_of_delivery WHERE shipment_id = '" + shipment + "'")).isZero();
    }

    @Test
    void genericTransitionToDelivered_stillNeedsAProof() {
        assertThatThrownBy(() -> shipments.transition(new TransitionCommand(shipment, ShipmentStatus.DELIVERED,
                Actor.COURIER, key(), null, null, null)))
                .hasMessageContaining("proof of delivery is required");
    }

    // --- erişim ve durum ---

    @Test
    void onlyTheAssignedCourier_mayUploadAndDeliver() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> pod.upload(shipment, stranger, PodFile.Kind.PHOTO, JPEG))
                .isInstanceOfSatisfying(ShipmentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_FOUND));
        assertThatThrownBy(() -> pod.deliver(new DeliverCommand(shipment, stranger, key(), photo.id(), null, LAT, LON,
                null, "Ada", null)))
                .isInstanceOfSatisfying(ShipmentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_FOUND));
        assertThat(shipments.get(shipment).status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void uploadAndDeliver_requireTheShipmentToBeOutForDelivery() {
        UUID assigned = assigned(courier);

        assertThatThrownBy(() -> pod.upload(assigned, courier, PodFile.Kind.PHOTO, JPEG))
                .isInstanceOfSatisfying(ShipmentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.ILLEGAL_TRANSITION));
        assertThatThrownBy(() -> pod.deliver(command(assigned, key(), UUID.randomUUID(), null, LAT, null)))
                .isInstanceOfSatisfying(ShipmentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.ILLEGAL_TRANSITION));
    }

    @Test
    void filesMustBelongToTheShipmentAndHaveTheRightKind() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        PodFile signature = pod.upload(shipment, courier, PodFile.Kind.SIGNATURE, PNG);
        UUID otherShipment = outForDelivery(courier);
        PodFile foreignPhoto = pod.upload(otherShipment, courier, PodFile.Kind.PHOTO, JPEG);

        assertInvalid(() -> pod.deliver(command(shipment, key(), foreignPhoto.id(), null, LAT, null)));
        assertInvalid(() -> pod.deliver(command(shipment, key(), signature.id(), null, LAT, null)));
        assertInvalid(() -> pod.deliver(command(shipment, key(), null, null, LAT, null)));
        assertInvalid(() -> pod.deliver(command(shipment, key(), photo.id(), photo.id(), LAT, null)));
        assertInvalid(() -> pod.deliver(command(shipment, key(), UUID.randomUUID(), null, LAT, null)));
        assertThat(shipments.get(shipment).status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void requestFieldsAreValidated() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);

        for (double latitude : new double[] {91, -91, Double.NaN}) {
            assertInvalid(() -> pod.deliver(command(shipment, key(), photo.id(), null, latitude, null)));
        }
        assertInvalid(() -> pod.deliver(new DeliverCommand(shipment, courier, key(), photo.id(), null, LAT, 181,
                null, "Ada", null)));
        assertInvalid(() -> pod.deliver(new DeliverCommand(shipment, courier, key(), photo.id(), null, LAT, LON,
                -1.0, "Ada", null)));
        assertInvalid(() -> pod.deliver(new DeliverCommand(shipment, courier, key(), photo.id(), null, LAT, LON,
                null, " ", null)));
        assertInvalid(() -> pod.deliver(new DeliverCommand(shipment, courier, key(), photo.id(), null, LAT, LON,
                null, "x".repeat(201), null)));
        assertInvalid(() -> pod.deliver(new DeliverCommand(shipment, courier, " ", photo.id(), null, LAT, LON,
                null, "Ada", null)));
    }

    // --- idempotency ve eşzamanlılık ---

    @Test
    void sameKeyAndPayloadIsReplayed_differentPayloadConflicts() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        String key = key();
        DeliverCommand command = command(shipment, key, photo.id(), null, INSIDE, null);

        ShipmentResult first = pod.deliver(command);
        ShipmentResult again = pod.deliver(command);

        assertThat(again.replayed()).isTrue();
        assertThat(again.shipment().version()).isEqualTo(first.shipment().version());
        assertThatThrownBy(() -> pod.deliver(command(shipment, key, photo.id(), null, INSIDE, null, "Someone Else")))
                .isInstanceOfSatisfying(ShipmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.IDEMPOTENCY_CONFLICT));
        assertThat(count("proof_of_delivery WHERE shipment_id = '" + shipment + "'")).isEqualTo(1);
    }

    @Test
    void deliveredShipmentCannotBeDeliveredAgainWithAnotherKey() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, null));

        assertThatThrownBy(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, null)))
                .isInstanceOfSatisfying(ShipmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.ILLEGAL_TRANSITION));
    }

    @Test
    void concurrentDeliveries_haveExactlyOneWinner() throws Exception {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> {
                    try {
                        pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, null));
                        return true;
                    } catch (ShipmentException e) {
                        return false;
                    }
                });
            }
            long winners = 0;
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                winners += result.get() ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("proof_of_delivery WHERE shipment_id = '" + shipment + "'")).isEqualTo(1);
        assertThat(shipments.get(shipment).version()).isEqualTo(6); // oluşturma + 5 geçiş + DELIVERED = v6
    }

    // --- OTP ---

    @Test
    void whenAnOtpWasIssued_itIsRequired() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        OtpService.Issued issued = otp.issue(shipment);

        assertOtp(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, null)), Reason.OTP_INVALID);
        assertOtp(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, wrong(issued.code()))),
                Reason.OTP_INVALID);
        assertThat(shipments.get(shipment).status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);

        pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, issued.code()));

        assertThat(shipments.get(shipment).status()).isEqualTo(ShipmentStatus.DELIVERED);
        assertThat(pod.getProof(shipment, s -> true).otpVerified()).isTrue();
    }

    @Test
    void theCodeIsStoredOnlyAsAnHmac() {
        OtpService.Issued issued = otp.issue(shipment);

        String stored = jdbc.queryForObject("SELECT code_hash FROM delivery_otps WHERE shipment_id = ?",
                String.class, shipment);

        assertThat(stored).matches("[0-9a-f]{64}").doesNotContain(issued.code());
    }

    @Test
    void wrongAttemptsArePersistedEvenThoughTheRequestFails_andLockAfterFive() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        OtpService.Issued issued = otp.issue(shipment);

        assertOtp(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, wrong(issued.code()))),
                Reason.OTP_INVALID);
        assertThat(attempts()).isEqualTo(1);

        for (int i = 0; i < 4; i++) {
            assertOtp(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, wrong(issued.code()))),
                    Reason.OTP_INVALID);
        }
        assertThat(attempts()).isEqualTo(5);

        // kilitli: doğru kod bile kabul edilmez
        assertOtp(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, issued.code())),
                Reason.OTP_LOCKED);
        assertThat(shipments.get(shipment).status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);

        // yeni kod kilidi açar ve sayacı sıfırlar
        OtpService.Issued fresh = otp.issue(shipment);
        assertThat(attempts()).isZero();
        pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, fresh.code()));
        assertThat(shipments.get(shipment).status()).isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void anExpiredOtpIsRejected_untilANewOneIsIssued() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        OtpService.Issued issued = otp.issue(shipment);
        jdbc.update("UPDATE delivery_otps SET issued_at = now() - interval '2 days', "
                + "expires_at = now() - interval '1 day' WHERE shipment_id = ?", shipment);

        assertOtp(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, issued.code())),
                Reason.OTP_LOCKED);
        OtpService.Issued fresh = otp.issue(shipment);
        pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, fresh.code()));
    }

    @Test
    void aCourierTooFarAway_doesNotBurnOtpAttempts() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        OtpService.Issued issued = otp.issue(shipment);

        assertThatThrownBy(() -> pod.deliver(command(shipment, key(), photo.id(), null, OUTSIDE, wrong(issued.code()))))
                .isInstanceOf(PodTooFarException.class);

        assertThat(attempts()).isZero();
    }

    @Test
    void everyVerificationOutcomeAndStatusTransitionIsCounted() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        OtpService.Issued issued = otp.issue(shipment);
        double tooFar = pods("too_far");
        double wrong = pods("otp_wrong");
        double locked = pods("otp_locked");
        double ok = pods("ok");
        double delivered = transitions("DELIVERED");

        assertThatThrownBy(() -> pod.deliver(command(shipment, key(), photo.id(), null, OUTSIDE, null)))
                .isInstanceOf(PodTooFarException.class);
        assertOtp(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, null)), Reason.OTP_INVALID);
        for (int i = 0; i < 5; i++) { // eksik kod deneme hakkı harcamaz, yanlış kod harcar
            assertOtp(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, wrong(issued.code()))),
                    Reason.OTP_INVALID);
        }
        assertOtp(() -> pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, issued.code())),
                Reason.OTP_LOCKED);
        assertThat(transitions("DELIVERED")).isEqualTo(delivered);
        pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, otp.issue(shipment).code()));

        assertThat(pods("too_far") - tooFar).isEqualTo(1);
        assertThat(pods("otp_wrong") - wrong).isEqualTo(6);
        assertThat(pods("otp_locked") - locked).isEqualTo(1);
        assertThat(pods("ok") - ok).isEqualTo(1);
        assertThat(transitions("DELIVERED") - delivered).isEqualTo(1);
    }

    private double pods(String outcome) {
        var counter = meters.find("routemesh.shipment.pod.verifications").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private double transitions(String toStatus) {
        var counter = meters.find("routemesh.shipment.transitions").tag("to_status", toStatus).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void issuingAnOtpForAFinishedShipment_isRejected() {
        PodFile photo = pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        pod.deliver(command(shipment, key(), photo.id(), null, INSIDE, null));

        assertThatThrownBy(() -> otp.issue(shipment)).isInstanceOfSatisfying(ShipmentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.ILLEGAL_TRANSITION));
        assertThatThrownBy(() -> otp.issue(UUID.randomUUID())).isInstanceOfSatisfying(ShipmentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.NOT_FOUND));
    }

    // --- dosya yükleme ---

    @Test
    void upload_detectsTheTypeFromTheBytes_andStoresTheObject() {
        PodFile png = pod.upload(shipment, courier, PodFile.Kind.PHOTO, PNG);
        PodFile jpeg = pod.upload(shipment, courier, PodFile.Kind.SIGNATURE, JPEG);

        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(png.objectKey()).isEqualTo("pod/" + shipment + "/" + png.id() + ".png");
        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(jpeg.objectKey()).endsWith(".jpg");
        assertThat(png.sha256()).matches("[0-9a-f]{64}");
        assertThat(png.sizeBytes()).isEqualTo(PNG.length);
        assertThat(storage.objects.get(png.objectKey())).isEqualTo(PNG);
        assertThat(png.uploadedBy()).isEqualTo(courier);
    }

    @Test
    void upload_rejectsNonImagesEmptyAndOversizedFiles() {
        assertThatThrownBy(() -> pod.upload(shipment, courier, PodFile.Kind.PHOTO, "GIF89a....".getBytes()))
                .isInstanceOfSatisfying(ShipmentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_MEDIA));
        assertThatThrownBy(() -> pod.upload(shipment, courier, PodFile.Kind.PHOTO, "<script>alert(1)</script>".getBytes()))
                .isInstanceOfSatisfying(ShipmentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_MEDIA));
        assertInvalid(() -> pod.upload(shipment, courier, PodFile.Kind.PHOTO, new byte[0]));

        byte[] atLimit = new byte[ProofOfDeliveryService.MAX_FILE_BYTES];
        System.arraycopy(JPEG, 0, atLimit, 0, JPEG.length);
        assertThat(pod.upload(shipment, courier, PodFile.Kind.PHOTO, atLimit).sizeBytes())
                .isEqualTo(ProofOfDeliveryService.MAX_FILE_BYTES);
        byte[] tooBig = Arrays.copyOf(atLimit, atLimit.length + 1);
        assertThatThrownBy(() -> pod.upload(shipment, courier, PodFile.Kind.PHOTO, tooBig))
                .isInstanceOfSatisfying(ShipmentException.class, e -> assertThat(e.getReason()).isEqualTo(Reason.PAYLOAD_TOO_LARGE));
        assertThat(count("pod_files WHERE shipment_id = '" + shipment + "'")).isEqualTo(1);
    }

    @Test
    void upload_isLimitedPerShipment() {
        for (int i = 0; i < 5; i++) {
            pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG);
        }

        assertInvalid(() -> pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG));
    }

    @Test
    void upload_whenStorageIsDown_failsWithoutLeavingARecord() {
        storage.failing = true;

        assertThatThrownBy(() -> pod.upload(shipment, courier, PodFile.Kind.PHOTO, JPEG))
                .isInstanceOfSatisfying(ShipmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.STORAGE_UNAVAILABLE));
        assertThat(count("pod_files WHERE shipment_id = '" + shipment + "'")).isZero();
    }

    // --- yardımcılar ---

    private DeliverCommand command(UUID id, String key, UUID photo, UUID signature, double latitude, String code) {
        return command(id, key, photo, signature, latitude, code, "Ada Lovelace");
    }

    private DeliverCommand command(UUID id, String key, UUID photo, UUID signature, double latitude, String code,
            String recipient) {
        return new DeliverCommand(id, courier, key, photo, signature, latitude, LON, 12.0, recipient, code);
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ShipmentException.class,
                e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_REQUEST));
    }

    private static void assertOtp(Runnable call, Reason reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ShipmentException.class,
                e -> assertThat(e.getReason()).isEqualTo(reason));
    }

    private static String wrong(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }

    private int attempts() {
        return jdbc.queryForObject("SELECT attempts FROM delivery_otps WHERE shipment_id = ?", Integer.class, shipment);
    }

    private int count(String fromWhere) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class);
    }

    private UUID assigned(UUID courierId) {
        UUID id = shipments.create(new CreateShipmentCommand(key(), UUID.randomUUID(), "Ada Lovelace", "+905551112233",
                "Moda Cd. 12, Kadikoy", LAT, LON, UUID.randomUUID(), 1001, 800, "TRY")).shipment().id();
        move(id, ShipmentStatus.PAID, Actor.SYSTEM, null);
        move(id, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE, null);
        move(id, ShipmentStatus.READY_FOR_DISPATCH, Actor.WAREHOUSE, null);
        move(id, ShipmentStatus.ASSIGNED, Actor.SYSTEM, courierId);
        return id;
    }

    private UUID outForDelivery(UUID courierId) {
        UUID id = assigned(courierId);
        move(id, ShipmentStatus.OUT_FOR_DELIVERY, Actor.COURIER, null);
        return id;
    }

    private void move(UUID id, ShipmentStatus to, Actor actor, UUID courierId) {
        shipments.transition(new TransitionCommand(id, to, actor, key(), null, courierId, null));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
