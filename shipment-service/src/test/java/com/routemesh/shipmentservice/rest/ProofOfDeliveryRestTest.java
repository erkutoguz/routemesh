package com.routemesh.shipmentservice.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;
import com.routemesh.shipmentservice.TestcontainersConfiguration;
import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.CreateShipmentCommand;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.service.OtpService;
import com.routemesh.shipmentservice.service.ShipmentService;
import com.routemesh.shipmentservice.storage.InMemoryPodStorage;
import com.routemesh.shipmentservice.storage.TestPodStorageConfiguration;

@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class, TestPodStorageConfiguration.class})
class ProofOfDeliveryRestTest {

    private static final double LAT = 41.0;
    private static final double LON = 29.0;
    private static final double INSIDE = LAT + 0.00044;   // ~48,9 m
    private static final double OUTSIDE = LAT + 0.00046;  // ~51,1 m
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ShipmentService shipments;

    @Autowired
    private OtpService otp;

    @Autowired
    private InMemoryPodStorage storage;

    private UUID merchant;
    private UUID courier;
    private UUID shipment;

    @BeforeEach
    void setUp() {
        storage.failing = false;
        merchant = UUID.randomUUID();
        courier = UUID.randomUUID();
        shipment = outForDelivery(merchant, courier);
    }

    // --- yükleme ---

    @Test
    void courierUploadsAPhoto() throws Exception {
        upload(shipment, "PHOTO", JPEG, courier.toString(), "COURIER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").exists())
                .andExpect(jsonPath("$.kind").value("PHOTO"))
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.sizeBytes").value(JPEG.length))
                .andExpect(jsonPath("$.sha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
    }

    @Test
    void uploadRejectsNonImagesIgnoringTheDeclaredType() throws Exception {
        MockMultipartFile fakeJpeg = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "GIF89a....".getBytes());

        mvc.perform(multipart("/api/v1/shipments/" + shipment + "/pod/files").file(fakeJpeg).param("kind", "PHOTO")
                        .header("Authorization", bearer(courier.toString(), "COURIER")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void uploadValidatesKindAndFilePart() throws Exception {
        String auth = bearer(courier.toString(), "COURIER");
        String path = "/api/v1/shipments/" + shipment + "/pod/files";

        mvc.perform(multipart(path).file(new MockMultipartFile("file", "p.jpg", "image/jpeg", JPEG))
                .param("kind", "VIDEO").header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(multipart(path).file(new MockMultipartFile("file", "p.jpg", "image/jpeg", JPEG))
                .header("Authorization", auth)).andExpect(status().isBadRequest());
        mvc.perform(multipart(path).param("kind", "PHOTO").header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadAndDeliverAreCourierOnly_andOnlyForTheAssignedCourier() throws Exception {
        for (String role : new String[] {"MERCHANT", "OPS", "WAREHOUSE", "WORKFLOW"}) {
            upload(shipment, "PHOTO", JPEG, UUID.randomUUID().toString(), role).andExpect(status().isForbidden());
            deliver(shipment, "{}", key(), UUID.randomUUID().toString(), role).andExpect(status().isForbidden());
        }
        upload(shipment, "PHOTO", JPEG, UUID.randomUUID().toString(), "COURIER").andExpect(status().isNotFound());
        deliver(shipment, body(UUID.randomUUID().toString(), null, INSIDE, "Ada", null), key(),
                UUID.randomUUID().toString(), "COURIER").andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/shipments/" + shipment + "/deliver").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().is4xxClientError());
    }

    @Test
    void uploadBeforeTheShipmentIsOnTheRoad_isAConflict() throws Exception {
        UUID assigned = assigned(merchant, courier);

        upload(assigned, "PHOTO", JPEG, courier.toString(), "COURIER").andExpect(status().isConflict());
    }

    @Test
    void uploadWhenStorageIsDown_is503() throws Exception {
        storage.failing = true;

        upload(shipment, "PHOTO", JPEG, courier.toString(), "COURIER").andExpect(status().isServiceUnavailable());
    }

    // --- teslimat ---

    @Test
    void deliverWithinFiftyMeters_deliversTheShipment() throws Exception {
        String photo = uploadedFile(shipment, "PHOTO");
        String signature = uploadedFile(shipment, "SIGNATURE");

        deliver(shipment, body(photo, signature, INSIDE, "Ada Lovelace", null), key(), courier.toString(), "COURIER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.courierId").value(courier.toString()));
    }

    @Test
    void deliverBeyondFiftyMeters_is422ProblemWithTheDistance() throws Exception {
        String photo = uploadedFile(shipment, "PHOTO");

        deliver(shipment, body(photo, null, OUTSIDE, "Ada", null), key(), courier.toString(), "COURIER")
                .andExpect(status().is(422))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/problem+json")))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.distanceMeters").value(org.hamcrest.Matchers.greaterThan(50.0)))
                .andExpect(jsonPath("$.maxDistanceMeters").value(50.0));
        assertThat(shipments.get(shipment).status()).isEqualTo(ShipmentStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void deliverRequestValidation() throws Exception {
        String photo = uploadedFile(shipment, "PHOTO");
        String courierId = courier.toString();

        deliver(shipment, body(photo, null, INSIDE, "Ada", null), null, courierId, "COURIER")
                .andExpect(status().isBadRequest());
        deliver(shipment, "{\"photoFileId\":\"" + photo + "\",\"recipientName\":\"Ada\"}", key(), courierId, "COURIER")
                .andExpect(status().isBadRequest());
        deliver(shipment, body(null, null, INSIDE, "Ada", null), key(), courierId, "COURIER")
                .andExpect(status().isBadRequest());
        deliver(shipment, body(photo, null, INSIDE, "", null), key(), courierId, "COURIER")
                .andExpect(status().isBadRequest());
        deliver(shipment, "not json", key(), courierId, "COURIER").andExpect(status().isBadRequest());
    }

    @Test
    void otpFailuresAre403ThenLocked423() throws Exception {
        String photo = uploadedFile(shipment, "PHOTO");
        String code = otp.issue(shipment).code();
        String wrong = "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);

        deliver(shipment, body(photo, null, INSIDE, "Ada", null), key(), courier.toString(), "COURIER")
                .andExpect(status().isForbidden());
        for (int i = 0; i < 5; i++) {
            deliver(shipment, body(photo, null, INSIDE, "Ada", wrong), key(), courier.toString(), "COURIER")
                    .andExpect(status().isForbidden());
        }
        deliver(shipment, body(photo, null, INSIDE, "Ada", code), key(), courier.toString(), "COURIER")
                .andExpect(status().isLocked());
    }

    @Test
    void deliverWithTheRightOtp_marksItVerified() throws Exception {
        String photo = uploadedFile(shipment, "PHOTO");
        String code = otp.issue(shipment).code();

        deliver(shipment, body(photo, null, INSIDE, "Ada", code), key(), courier.toString(), "COURIER")
                .andExpect(status().isOk());

        proofAs(shipment, merchant.toString(), "MERCHANT").andExpect(jsonPath("$.otpVerified").value(true));
    }

    @Test
    void sameKeyIsReplayedAndAnotherKeyAfterDeliveryIsAConflict() throws Exception {
        String photo = uploadedFile(shipment, "PHOTO");
        String key = key();
        String body = body(photo, null, INSIDE, "Ada", null);

        deliver(shipment, body, key, courier.toString(), "COURIER").andExpect(status().isOk());
        deliver(shipment, body, key, courier.toString(), "COURIER").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(6));
        deliver(shipment, body, key(), courier.toString(), "COURIER").andExpect(status().isConflict());
    }

    // --- okuma ---

    @Test
    void proofIsHiddenUntilDelivered_thenVisibleToOwnerOpsAndCourierOnly() throws Exception {
        proofAs(shipment, merchant.toString(), "MERCHANT").andExpect(status().isNotFound());

        String photo = uploadedFile(shipment, "PHOTO");
        deliver(shipment, body(photo, null, INSIDE, "Ada Lovelace", null), key(), courier.toString(), "COURIER")
                .andExpect(status().isOk());

        proofAs(shipment, merchant.toString(), "MERCHANT").andExpect(status().isOk())
                .andExpect(jsonPath("$.shipmentId").value(shipment.toString()))
                .andExpect(jsonPath("$.courierId").value(courier.toString()))
                .andExpect(jsonPath("$.recipientName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.deliveredLocation.latitude").value(INSIDE))
                .andExpect(jsonPath("$.distanceMeters").value(org.hamcrest.Matchers.lessThanOrEqualTo(50.0)))
                .andExpect(jsonPath("$.otpVerified").value(false))
                .andExpect(jsonPath("$.photo.fileId").value(photo))
                .andExpect(jsonPath("$.signature").doesNotExist());
        proofAs(shipment, "ops", "OPS").andExpect(status().isOk());
        proofAs(shipment, courier.toString(), "COURIER").andExpect(status().isOk());
        proofAs(shipment, UUID.randomUUID().toString(), "MERCHANT").andExpect(status().isNotFound());
        proofAs(shipment, UUID.randomUUID().toString(), "COURIER").andExpect(status().isNotFound());
        proofAs(shipment, "wh", "WAREHOUSE").andExpect(status().isForbidden());
        proofAs(shipment, "sa", "WORKFLOW").andExpect(status().isForbidden());
    }

    @Test
    void filesAreStreamedToAuthorizedActorsOnly() throws Exception {
        String photo = uploadedFile(shipment, "PHOTO");
        String path = "/api/v1/shipments/" + shipment + "/pod/files/" + photo;

        // Yüklenmiş ama kanıta bağlanmamış dosyayı yalnızca kurye görür
        fileAs(path, courier.toString(), "COURIER").andExpect(status().isOk());
        fileAs(path, merchant.toString(), "MERCHANT").andExpect(status().isNotFound());
        fileAs(path, "ops", "OPS").andExpect(status().isNotFound());

        deliver(shipment, body(photo, null, INSIDE, "Ada", null), key(), courier.toString(), "COURIER")
                .andExpect(status().isOk());

        MvcResult result = fileAs(path, merchant.toString(), "MERCHANT").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(JPEG);
        fileAs(path, "ops", "OPS").andExpect(status().isOk());
        fileAs(path, UUID.randomUUID().toString(), "MERCHANT").andExpect(status().isNotFound());
        fileAs(path, UUID.randomUUID().toString(), "COURIER").andExpect(status().isNotFound());
        fileAs(path, "wh", "WAREHOUSE").andExpect(status().isForbidden());
    }

    @Test
    void aFileIsOnlyReachableThroughItsOwnShipment() throws Exception {
        String photo = uploadedFile(shipment, "PHOTO");
        UUID otherShipment = outForDelivery(merchant, courier);

        fileAs("/api/v1/shipments/" + otherShipment + "/pod/files/" + photo, courier.toString(), "COURIER")
                .andExpect(status().isNotFound());
        fileAs("/api/v1/shipments/" + shipment + "/pod/files/" + UUID.randomUUID(), courier.toString(), "COURIER")
                .andExpect(status().isNotFound());
    }

    @Test
    void readingAFileWhenStorageIsDown_is503() throws Exception {
        String photo = uploadedFile(shipment, "PHOTO");
        storage.failing = true;

        fileAs("/api/v1/shipments/" + shipment + "/pod/files/" + photo, courier.toString(), "COURIER")
                .andExpect(status().isServiceUnavailable());
    }

    // --- yardımcılar ---

    private ResultActions upload(UUID id, String kind, byte[] content, String subject, String role) throws Exception {
        return mvc.perform(multipart("/api/v1/shipments/" + id + "/pod/files")
                .file(new MockMultipartFile("file", "photo.jpg", "image/jpeg", content))
                .param("kind", kind)
                .header("Authorization", bearer(subject, role)));
    }

    private String uploadedFile(UUID id, String kind) throws Exception {
        MvcResult result = upload(id, kind, JPEG, courier.toString(), "COURIER").andExpect(status().isCreated()).andReturn();
        String json = result.getResponse().getContentAsString();
        return json.replaceAll(".*\"fileId\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions deliver(UUID id, String json, String key, String subject, String role) throws Exception {
        var request = post("/api/v1/shipments/" + id + "/deliver")
                .header("Authorization", bearer(subject, role))
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private ResultActions proofAs(UUID id, String subject, String role) throws Exception {
        return mvc.perform(get("/api/v1/shipments/" + id + "/pod").header("Authorization", bearer(subject, role)));
    }

    private ResultActions fileAs(String path, String subject, String role) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer(subject, role)));
    }

    private static String body(String photo, String signature, double latitude, String recipient, String otp) {
        StringBuilder json = new StringBuilder("{");
        if (photo != null) {
            json.append("\"photoFileId\":\"").append(photo).append("\",");
        }
        if (signature != null) {
            json.append("\"signatureFileId\":\"").append(signature).append("\",");
        }
        json.append("\"latitude\":").append(latitude).append(",\"longitude\":").append(LON)
                .append(",\"accuracyMeters\":8.5,\"recipientName\":\"").append(recipient).append("\"");
        if (otp != null) {
            json.append(",\"otp\":\"").append(otp).append("\"");
        }
        return json.append("}").toString();
    }

    private UUID assigned(UUID merchantId, UUID courierId) {
        UUID id = shipments.create(new CreateShipmentCommand(key(), merchantId, "Ada Lovelace", "+905551112233",
                "Moda Cd. 12, Kadikoy", LAT, LON, UUID.randomUUID(), 1001, 800, "TRY")).shipment().id();
        move(id, ShipmentStatus.PAID, Actor.SYSTEM, null);
        move(id, ShipmentStatus.AT_WAREHOUSE, Actor.WAREHOUSE, null);
        move(id, ShipmentStatus.READY_FOR_DISPATCH, Actor.WAREHOUSE, null);
        move(id, ShipmentStatus.ASSIGNED, Actor.SYSTEM, courierId);
        return id;
    }

    private UUID outForDelivery(UUID merchantId, UUID courierId) {
        UUID id = assigned(merchantId, courierId);
        move(id, ShipmentStatus.OUT_FOR_DELIVERY, Actor.COURIER, null);
        return id;
    }

    private void move(UUID id, ShipmentStatus to, Actor actor, UUID courierId) {
        shipments.transition(new TransitionCommand(id, to, actor, key(), null, courierId, null));
    }

    private static String bearer(String subject, String role) {
        return "Bearer " + TestJwts.token(subject, role);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
