package com.routemesh.notificationservice.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.routemesh.notificationservice.TestcontainersConfiguration;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "routemesh.notification.consumer-enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class NotificationRestTest {

    private static final String BASE = "/api/v1/notifications";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID merchant = UUID.randomUUID();
    private final UUID courier = UUID.randomUUID();

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM notifications").update();
    }

    // --- güvenlik ---

    @Test
    void withoutATokenEverythingIsUnauthorized() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/read")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyMerchantsAndCouriersHaveAnInbox() throws Exception {
        for (String role : new String[] {"OPS", "WAREHOUSE", "WORKFLOW"}) {
            String token = bearer(UUID.randomUUID().toString(), role);
            mvc.perform(get(BASE).header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/unread-count").header("Authorization", token)).andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/read").header("Authorization", token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aTokenWithBothRolesOrANonUuidSubjectIsForbidden() throws Exception {
        mvc.perform(get(BASE).header("Authorization", bearer(UUID.randomUUID().toString(), "MERCHANT", "COURIER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE).header("Authorization", bearer("not-a-uuid", "COURIER")))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/unread-count").header("Authorization", bearer("not-a-uuid", "MERCHANT")))
                .andExpect(status().isForbidden());
    }

    // --- listeleme ---

    @Test
    void anEmptyInboxIsAnEmptyPage() throws Exception {
        mvc.perform(get(BASE).header("Authorization", merchantToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        mvc.perform(get(BASE + "/unread-count").header("Authorization", merchantToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void theInboxShowsOnlyTheCallersInAppNotificationsNewestFirst() throws Exception {
        UUID shipment = UUID.randomUUID();
        UUID first = insert("MERCHANT", merchant, "IN_APP", "SHIPMENT_LOST", shipment, "{\"status\":\"LOST\"}");
        UUID second = insert("MERCHANT", merchant, "IN_APP", "COURIER_APPROACHING", shipment, "{\"distanceMeters\":87}");
        insert("MERCHANT", UUID.randomUUID(), "IN_APP", "SHIPMENT_LOST", shipment, "{}");
        insert("COURIER", courier, "IN_APP", "NEW_OFFER", shipment, "{}");
        insert("RECIPIENT", merchant, "SMS", "DELIVERY_OTP", shipment, "{}");

        mvc.perform(get(BASE).header("Authorization", merchantToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second.toString()))
                .andExpect(jsonPath("$.items[0].template").value("COURIER_APPROACHING"))
                .andExpect(jsonPath("$.items[0].shipmentId").value(shipment.toString()))
                .andExpect(jsonPath("$.items[0].params.distanceMeters").value(87))
                .andExpect(jsonPath("$.items[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.items[1].id").value(first.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void sameSubjectButADifferentRoleSeesNothing() throws Exception {
        insert("MERCHANT", merchant, "IN_APP", "SHIPMENT_LOST", null, "{}");

        mvc.perform(get(BASE).header("Authorization", bearer(merchant.toString(), "COURIER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void cursorPagingWalksEveryRowOnceEvenWhenTimestampsAreEqual() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            expected.add(insertAt("2020-01-01T00:00:00Z", merchant, "T" + i));
        }
        for (int i = 3; i < 6; i++) {
            expected.add(insert("MERCHANT", merchant, "IN_APP", "T" + i, null, "{}"));
        }
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MvcResult result = mvc.perform(get(BASE + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor))
                            .header("Authorization", merchantToken()))
                    .andExpect(status().isOk()).andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            body.get("items").forEach(item -> seen.add(UUID.fromString(item.get("id").asString())));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(6).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(seen.subList(0, 3)).containsExactlyInAnyOrderElementsOf(expected.subList(3, 6));
    }

    @Test
    void theUnreadFilterAndCounterIgnoreReadNotificationsAndOtherChannels() throws Exception {
        UUID read = insert("COURIER", courier, "IN_APP", "NEW_OFFER", null, "{}");
        UUID unread = insert("COURIER", courier, "IN_APP", "NEW_OFFER", null, "{}");
        insert("COURIER", courier, "SMS", "X", null, "{}");
        mvc.perform(post(BASE + "/" + read + "/read").header("Authorization", courierToken()))
                .andExpect(status().isOk());

        mvc.perform(get(BASE + "?unread=true").header("Authorization", courierToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(unread.toString()));
        mvc.perform(get(BASE).header("Authorization", courierToken()))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get(BASE + "/unread-count").header("Authorization", courierToken()))
                .andExpect(jsonPath("$.unread").value(1));
    }

    @Test
    void invalidPagingParametersAreRejected() throws Exception {
        for (String query : new String[] {"?limit=0", "?limit=101", "?limit=abc", "?cursor=%25%25", "?cursor=bm9wZQ",
                "?unread=maybe"}) {
            mvc.perform(get(BASE + query).header("Authorization", merchantToken()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE + "?limit=100").header("Authorization", merchantToken())).andExpect(status().isOk());
    }

    // --- okundu işaretleme ---

    @Test
    void markingReadIsIdempotentAndKeepsTheFirstReadTime() throws Exception {
        UUID id = insert("MERCHANT", merchant, "IN_APP", "SHIPMENT_LOST", null, "{}");

        MvcResult first = mvc.perform(post(BASE + "/" + id + "/read").header("Authorization", merchantToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.readAt").exists()).andReturn();
        MvcResult second = mvc.perform(post(BASE + "/" + id + "/read").header("Authorization", merchantToken()))
                .andExpect(status().isOk()).andReturn();

        assertThat(json.readTree(second.getResponse().getContentAsString()).get("readAt"))
                .isEqualTo(json.readTree(first.getResponse().getContentAsString()).get("readAt"));
        mvc.perform(get(BASE + "/unread-count").header("Authorization", merchantToken()))
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void someoneElsesOrMissingOrSmsNotificationsAreAllTheSameNotFound() throws Exception {
        UUID others = insert("MERCHANT", UUID.randomUUID(), "IN_APP", "SHIPMENT_LOST", null, "{}");
        UUID otherRole = insert("COURIER", courier, "IN_APP", "NEW_OFFER", null, "{}");
        UUID sms = insert("RECIPIENT", merchant, "SMS", "DELIVERY_OTP", null, "{}");
        String ownSub = bearer(courier.toString(), "MERCHANT");

        List<String> bodies = new ArrayList<>();
        for (UUID id : new UUID[] {others, otherRole, sms, UUID.randomUUID()}) {
            String token = id.equals(otherRole) ? ownSub : merchantToken();
            MvcResult result = mvc.perform(post(BASE + "/" + id + "/read").header("Authorization", token))
                    .andExpect(status().isNotFound()).andReturn();
            bodies.add(result.getResponse().getContentAsString().replace(id.toString(), "ID"));
        }

        assertThat(bodies).containsOnly(bodies.getFirst());
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE read_at IS NOT NULL").query(Long.class)
                .single()).isZero();
    }

    @Test
    void aMalformedNotificationIdIsABadRequest() throws Exception {
        mvc.perform(post(BASE + "/not-a-uuid/read").header("Authorization", merchantToken()))
                .andExpect(status().isBadRequest());
    }

    private UUID insert(String type, UUID recipient, String channel, String template, UUID shipment, String params) {
        return jdbc.sql("INSERT INTO notifications (event_id, template, recipient_type, recipient_id, channel, "
                        + "shipment_id, params) VALUES (:e, :t, :type, :r, :c, :s, CAST(:p AS jsonb)) RETURNING id")
                .param("e", UUID.randomUUID()).param("t", template).param("type", type).param("r", recipient)
                .param("c", channel).param("s", shipment).param("p", params)
                .query(UUID.class).single();
    }

    private UUID insertAt(String createdAt, UUID recipient, String template) {
        return jdbc.sql("INSERT INTO notifications (event_id, template, recipient_type, recipient_id, channel, "
                        + "created_at) VALUES (:e, :t, 'MERCHANT', :r, 'IN_APP', CAST(:at AS timestamptz)) RETURNING id")
                .param("e", UUID.randomUUID()).param("t", template).param("r", recipient).param("at", createdAt)
                .query(UUID.class).single();
    }

    private String merchantToken() {
        return bearer(merchant.toString(), "MERCHANT");
    }

    private String courierToken() {
        return bearer(courier.toString(), "COURIER");
    }

    private static String bearer(String subject, String... roles) {
        return "Bearer " + TestJwts.token(subject, roles);
    }
}
