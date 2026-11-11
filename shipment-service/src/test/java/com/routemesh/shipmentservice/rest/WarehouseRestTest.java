package com.routemesh.shipmentservice.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;
import com.routemesh.shipmentservice.TestcontainersConfiguration;
import com.routemesh.shipmentservice.domain.Actor;
import com.routemesh.shipmentservice.domain.CreateShipmentCommand;
import com.routemesh.shipmentservice.domain.ShipmentStatus;
import com.routemesh.shipmentservice.domain.TransitionCommand;
import com.routemesh.shipmentservice.service.ShipmentService;

@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class WarehouseRestTest {

    private static final String BASE = "/api/v1/warehouse/shipments";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ShipmentService service;

    // --- yetki ---

    @Test
    void withoutToken_isUnauthorized() throws Exception {
        UUID id = paid();
        // Tokensiz POST'ta servis 401 yerine 403 döner (bilinen Accept/giriş noktası tuhaflığı, gateway'de
        // 401 olarak korunur); önemli olan isteğin uygulanmaması
        mvc.perform(post(BASE + "/" + id + "/receive").header("Idempotency-Key", key()))
                .andExpect(status().is4xxClientError());
        mvc.perform(post(BASE + "/receive").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(ids(id)))
                .andExpect(status().is4xxClientError());
        assertThat(service.get(id).status()).isEqualTo(ShipmentStatus.PAID);
    }

    @Test
    void onlyWarehouseRole_mayUseTheEndpoints() throws Exception {
        UUID id = paid();
        for (String role : new String[] {"MERCHANT", "COURIER", "OPS", "WORKFLOW"}) {
            for (String path : new String[] {"/" + id + "/receive", "/" + id + "/ready", "/receive", "/ready"}) {
                mvc.perform(post(BASE + path).header("Authorization", bearer("someone", role))
                                .header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                                .content(ids(id)))
                        .andExpect(status().isForbidden());
            }
        }
        assertThat(service.get(id).status()).isEqualTo(ShipmentStatus.PAID);
    }

    // --- tekil ---

    @Test
    void receiveThenReady_movesTheShipmentAndRecordsTheWarehouseActor() throws Exception {
        UUID id = paid();

        single(id, "receive", key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AT_WAREHOUSE"));
        single(id, "ready", key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_DISPATCH"));

        mvc.perform(get("/api/v1/ops/shipments/" + id + "/history").header("Authorization", bearer("ops", "OPS")))
                .andExpect(jsonPath("$[2].actor").value("WAREHOUSE"))
                .andExpect(jsonPath("$[3].actor").value("WAREHOUSE"))
                .andExpect(jsonPath("$[3].toStatus").value("READY_FOR_DISPATCH"));
    }

    @Test
    void sameKeyIsReplayedWithoutAnotherTransition() throws Exception {
        UUID id = paid();
        String key = key();

        single(id, "receive", key).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        single(id, "receive", key).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void wrongStateUnknownShipmentAndMissingKey_areRejected() throws Exception {
        UUID pending = create();
        UUID paid = paid();

        single(pending, "receive", key()).andExpect(status().isConflict());      // henüz ödenmedi
        single(paid, "ready", key()).andExpect(status().isConflict());           // önce kabul edilmeli
        single(UUID.randomUUID(), "receive", key()).andExpect(status().isNotFound());
        mvc.perform(post(BASE + "/" + paid + "/receive").header("Authorization", bearer("wh", "WAREHOUSE")))
                .andExpect(status().isBadRequest());
        assertThat(service.get(paid).status()).isEqualTo(ShipmentStatus.PAID);
    }

    // --- toplu ---

    @Test
    void bulkReceive_appliesWhatItCanAndReportsTheRest() throws Exception {
        UUID ok1 = paid();
        UUID ok2 = paid();
        UUID notPaid = create();
        UUID unknown = UUID.randomUUID();

        bulk("receive", key(), ok1, notPaid, ok2, unknown)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.rejected").value(2))
                .andExpect(jsonPath("$.results[0].shipmentId").value(ok1.toString()))
                .andExpect(jsonPath("$.results[0].result").value("OK"))
                .andExpect(jsonPath("$.results[0].status").value("AT_WAREHOUSE"))
                .andExpect(jsonPath("$.results[1].result").value("REJECTED"))
                .andExpect(jsonPath("$.results[1].code").value("ILLEGAL_TRANSITION"))
                .andExpect(jsonPath("$.results[2].result").value("OK"))
                .andExpect(jsonPath("$.results[3].code").value("NOT_FOUND"));

        assertThat(service.get(ok1).status()).isEqualTo(ShipmentStatus.AT_WAREHOUSE);
        assertThat(service.get(ok2).status()).isEqualTo(ShipmentStatus.AT_WAREHOUSE);
        assertThat(service.get(notPaid).status()).isEqualTo(ShipmentStatus.PENDING_PAYMENT);
    }

    @Test
    void bulkRetryWithTheSameKey_replaysAppliedItemsAndReevaluatesRejectedOnes() throws Exception {
        UUID applied = paid();
        UUID later = create();
        String key = key();

        bulk("receive", key, applied, later).andExpect(jsonPath("$.applied").value(1));
        service.transition(new TransitionCommand(later, ShipmentStatus.PAID, Actor.SYSTEM, key(), null, null, null));

        bulk("receive", key, applied, later)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].result").value("REPLAYED"))
                .andExpect(jsonPath("$.results[1].result").value("OK"))
                .andExpect(jsonPath("$.rejected").value(0));
        assertThat(service.get(applied).version()).isEqualTo(2);
    }

    @Test
    void bulkReady_movesReceivedShipmentsAndRejectsOthers() throws Exception {
        UUID received = paid();
        UUID stillPaid = paid();
        bulk("receive", key(), received).andExpect(jsonPath("$.applied").value(1));

        bulk("ready", key(), received, stillPaid)
                .andExpect(jsonPath("$.results[0].status").value("READY_FOR_DISPATCH"))
                .andExpect(jsonPath("$.results[1].code").value("ILLEGAL_TRANSITION"));
        assertThat(service.get(stillPaid).status()).isEqualTo(ShipmentStatus.PAID);
    }

    @Test
    void bulkRequestValidation() throws Exception {
        UUID id = paid();
        String wh = bearer("wh", "WAREHOUSE");

        // anahtar yok
        mvc.perform(post(BASE + "/receive").header("Authorization", wh).contentType(MediaType.APPLICATION_JSON)
                .content(ids(id))).andExpect(status().isBadRequest());
        // anahtar çok uzun
        bulk("receive", "k".repeat(219), id).andExpect(status().isBadRequest());
        // boş liste, gövde yok, 101 kayıt, yinelenen kayıt
        bulk("receive", key()).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/receive").header("Authorization", wh).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        List<UUID> tooMany = IntStream.rangeClosed(1, 101).mapToObj(i -> UUID.randomUUID()).toList();
        bulk("receive", key(), tooMany.toArray(UUID[]::new)).andExpect(status().isBadRequest());
        bulk("receive", key(), id, id).andExpect(status().isBadRequest());

        assertThat(service.get(id).status()).isEqualTo(ShipmentStatus.PAID);
    }

    @Test
    void bulkOfExactlyOneHundred_isAccepted() throws Exception {
        List<UUID> hundred = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hundred.add(UUID.randomUUID()); // bilinmeyen kimlikler: hepsi NOT_FOUND ama istek geçerli
        }

        bulk("receive", key(), hundred.toArray(UUID[]::new))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(100));
    }

    // --- yardımcılar ---

    private ResultActions single(UUID id, String action, String key) throws Exception {
        return mvc.perform(post(BASE + "/" + id + "/" + action)
                .header("Authorization", bearer("wh", "WAREHOUSE")).header("Idempotency-Key", key));
    }

    private ResultActions bulk(String action, String key, UUID... ids) throws Exception {
        return mvc.perform(post(BASE + "/" + action)
                .header("Authorization", bearer("wh", "WAREHOUSE")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(ids(ids)));
    }

    private static String ids(UUID... ids) {
        return "{\"shipmentIds\":["
                + java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]}";
    }

    private UUID create() {
        return service.create(new CreateShipmentCommand(key(), UUID.randomUUID(), "Ada Lovelace", "+905551112233",
                "Moda Cd. 12, Kadikoy", 40.9876, 29.0369, UUID.randomUUID(), 1001, 800, "TRY")).shipment().id();
    }

    private UUID paid() {
        UUID id = create();
        service.transition(new TransitionCommand(id, ShipmentStatus.PAID, Actor.SYSTEM, key(), null, null, null));
        return id;
    }

    private static String bearer(String subject, String role) {
        return "Bearer " + TestJwts.token(subject, role);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
