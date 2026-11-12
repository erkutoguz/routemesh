package com.routemesh.searchservice.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;
import com.routemesh.searchservice.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "routemesh.search.consumer-enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class ShipmentSearchRestTest {

    private static final String SEARCH = "/api/v1/search/shipments";
    private static final String OPS = "/api/v1/ops/shipments";
    private static final String WAREHOUSE = "/api/v1/warehouse/shipments";
    private static final String DAY = "2030-03-01T";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private final UUID merchant = UUID.randomUUID();
    private final UUID otherMerchant = UUID.randomUUID();
    private final UUID courier = UUID.randomUUID();

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM shipment_view").update();
    }

    // --- güvenlik ---

    @Test
    void withoutATokenEverythingIsUnauthorized() throws Exception {
        for (String path : new String[] {SEARCH, OPS, WAREHOUSE}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void theRoleMatrixIsEnforcedOnEveryEndpoint() throws Exception {
        String sub = UUID.randomUUID().toString();
        for (String role : new String[] {"OPS", "WAREHOUSE", "COURIER", "WORKFLOW"}) {
            mvc.perform(get(SEARCH).header("Authorization", bearer(sub, role))).andExpect(status().isForbidden());
        }
        for (String role : new String[] {"MERCHANT", "WAREHOUSE", "COURIER", "WORKFLOW"}) {
            mvc.perform(get(OPS).header("Authorization", bearer(sub, role))).andExpect(status().isForbidden());
        }
        for (String role : new String[] {"MERCHANT", "COURIER", "WORKFLOW"}) {
            mvc.perform(get(WAREHOUSE).header("Authorization", bearer(sub, role))).andExpect(status().isForbidden());
        }
        mvc.perform(get(SEARCH).header("Authorization", bearer(sub, "MERCHANT"))).andExpect(status().isOk());
        mvc.perform(get(OPS).header("Authorization", bearer(sub, "OPS"))).andExpect(status().isOk());
        mvc.perform(get(WAREHOUSE).header("Authorization", bearer(sub, "WAREHOUSE"))).andExpect(status().isOk());
        mvc.perform(get(WAREHOUSE).header("Authorization", bearer(sub, "OPS"))).andExpect(status().isOk());
    }

    @Test
    void aMerchantTokenWithANonUuidSubjectIsForbidden() throws Exception {
        mvc.perform(get(SEARCH).header("Authorization", bearer("not-a-uuid", "MERCHANT")))
                .andExpect(status().isForbidden());
    }

    // --- sahiplik ---

    @Test
    void aMerchantSeesOnlyItsOwnShipmentsNewestFirst() throws Exception {
        UUID older = insert(merchant, "PAID", null, DAY + "10:00:00Z");
        UUID newer = insert(merchant, "ASSIGNED", courier, DAY + "11:00:00Z");
        insert(otherMerchant, "PAID", null, DAY + "12:00:00Z");

        mvc.perform(get(SEARCH).header("Authorization", merchantToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newer.toString()))
                .andExpect(jsonPath("$.items[0].merchantId").value(merchant.toString()))
                .andExpect(jsonPath("$.items[0].status").value("ASSIGNED"))
                .andExpect(jsonPath("$.items[0].courierId").value(courier.toString()))
                .andExpect(jsonPath("$.items[0].amount.amountMinor").value(7500))
                .andExpect(jsonPath("$.items[0].amount.currency").value("TRY"))
                .andExpect(jsonPath("$.items[1].id").value(older.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void askingForAnotherMerchantIsForbiddenButItsOwnIdIsFine() throws Exception {
        insert(merchant, "PAID", null, DAY + "10:00:00Z");
        insert(otherMerchant, "PAID", null, DAY + "11:00:00Z");

        mvc.perform(get(SEARCH + "?merchantId=" + otherMerchant).header("Authorization", merchantToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get(SEARCH + "?merchantId=" + merchant).header("Authorization", merchantToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void opsSeesEveryoneAndCanNarrowByMerchant() throws Exception {
        insert(merchant, "PAID", null, DAY + "10:00:00Z");
        insert(otherMerchant, "PAID", null, DAY + "11:00:00Z");

        mvc.perform(get(OPS).header("Authorization", opsToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get(OPS + "?merchantId=" + otherMerchant).header("Authorization", opsToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].merchantId").value(otherMerchant.toString()));
    }

    // --- taslak satırlar ---

    @Test
    void skeletonRowsNeverAppearInAnyList() throws Exception {
        insert(merchant, "PAID", null, DAY + "10:00:00Z");
        for (String status : new String[] {"PAID", "AT_WAREHOUSE", "ASSIGNED"}) {
            jdbc.sql("INSERT INTO shipment_view (id, status, courier_id, version, created_at, updated_at) "
                            + "VALUES (:id, :s, :c, 3, CAST(:at AS timestamptz), CAST(:at AS timestamptz))")
                    .param("id", UUID.randomUUID()).param("s", status).param("c", courier)
                    .param("at", DAY + "13:00:00Z").update();
        }

        mvc.perform(get(SEARCH).header("Authorization", merchantToken()))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(OPS).header("Authorization", opsToken()))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(OPS + "?courierId=" + courier).header("Authorization", opsToken()))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get(WAREHOUSE).header("Authorization", warehouseToken()))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    // --- süzgeçler ---

    @Test
    void statusCourierAndDateFiltersCombine() throws Exception {
        UUID target = insert(merchant, "ASSIGNED", courier, DAY + "10:00:00Z");
        insert(merchant, "ASSIGNED", UUID.randomUUID(), DAY + "10:00:00.000001Z");
        insert(merchant, "PAID", courier, DAY + "10:00:00.000002Z");
        insert(merchant, "ASSIGNED", courier, DAY + "18:00:00Z");

        mvc.perform(get(SEARCH + "?status=ASSIGNED&courierId=" + courier + "&from=" + DAY + "09:00:00Z&to=" + DAY
                        + "12:00:00Z").header("Authorization", merchantToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(target.toString()));
        mvc.perform(get(SEARCH + "?status=ASSIGNED,PAID").header("Authorization", merchantToken()))
                .andExpect(jsonPath("$.items.length()").value(4));
        mvc.perform(get(SEARCH + "?status=PAID&status=ASSIGNED&courierId=" + courier)
                        .header("Authorization", merchantToken()))
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    void fromIsInclusiveAndToIsExclusive() throws Exception {
        insert(merchant, "PAID", null, DAY + "10:00:00Z");
        insert(merchant, "PAID", null, DAY + "11:00:00Z");
        insert(merchant, "PAID", null, DAY + "12:00:00Z");

        mvc.perform(get(SEARCH + "?from=" + DAY + "10:00:00Z&to=" + DAY + "12:00:00Z")
                        .header("Authorization", merchantToken()))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get(SEARCH + "?from=" + DAY + "12:00:00Z").header("Authorization", merchantToken()))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(SEARCH + "?to=" + DAY + "10:00:00Z").header("Authorization", merchantToken()))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void warehouseDefaultsToTheIntakeStatusesAndAcceptsAnExplicitSet() throws Exception {
        insert(merchant, "PAID", null, DAY + "10:00:00Z");
        insert(merchant, "AT_WAREHOUSE", null, DAY + "10:01:00Z");
        insert(merchant, "READY_FOR_DISPATCH", null, DAY + "10:02:00Z");
        insert(merchant, "ASSIGNED", courier, DAY + "10:03:00Z");
        insert(merchant, "DELIVERED", courier, DAY + "10:04:00Z");

        mvc.perform(get(WAREHOUSE).header("Authorization", warehouseToken()))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].status").value("READY_FOR_DISPATCH"));
        mvc.perform(get(WAREHOUSE + "?status=AT_WAREHOUSE").header("Authorization", opsWarehouseToken()))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get(WAREHOUSE + "?status=DELIVERED,ASSIGNED").header("Authorization", warehouseToken()))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    // --- sayfalama ---

    @Test
    void cursorPagingWalksEveryRowOnceEvenWhenTimestampsAreEqual() throws Exception {
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            expected.add(insert(merchant, "PAID", null, DAY + "10:00:00Z"));
        }
        for (int i = 0; i < 3; i++) {
            expected.add(insert(merchant, "PAID", null, DAY + "11:0" + i + ":00Z"));
        }
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MvcResult result = mvc.perform(get(SEARCH + "?limit=3" + (cursor == null ? "" : "&cursor=" + cursor))
                            .header("Authorization", merchantToken()))
                    .andExpect(status().isOk()).andReturn();
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            body.get("items").forEach(item -> seen.add(UUID.fromString(item.get("id").asString())));
            cursor = body.get("nextCursor").isNull() ? null : body.get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(7).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(seen.subList(0, 3)).containsExactlyInAnyOrderElementsOf(expected.subList(4, 7));
    }

    @Test
    void invalidParametersAreBadRequests() throws Exception {
        String token = merchantToken();
        for (String query : new String[] {"?limit=0", "?limit=101", "?limit=abc", "?cursor=%25%25", "?cursor=bm9wZQ",
                "?status=NOPE", "?status=PAID,NOPE", "?from=yesterday", "?to=2030-13-45", "?courierId=x",
                "?from=" + DAY + "12:00:00Z&to=" + DAY + "12:00:00Z", "?from=" + DAY + "13:00:00Z&to=" + DAY
                        + "12:00:00Z"}) {
            mvc.perform(get(SEARCH + query).header("Authorization", token)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(OPS + "?merchantId=x").header("Authorization", opsToken()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(WAREHOUSE + "?status=NOPE").header("Authorization", warehouseToken()))
                .andExpect(status().isBadRequest());
        mvc.perform(get(SEARCH + "?limit=100").header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    void errorsArriveAsProblemDetails() throws Exception {
        mvc.perform(get(SEARCH + "?limit=0").header("Authorization", merchantToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists());
    }

    private UUID insert(UUID merchantId, String status, UUID courierId, String createdAt) {
        return jdbc.sql("INSERT INTO shipment_view (id, merchant_id, status, courier_id, version, amount_minor, "
                        + "currency, created_at, updated_at) VALUES (:id, :m, :s, :c, 1, 7500, 'TRY', "
                        + "CAST(:at AS timestamptz), CAST(:at AS timestamptz)) RETURNING id")
                .param("id", UUID.randomUUID()).param("m", merchantId).param("s", status).param("c", courierId)
                .param("at", createdAt).query(UUID.class).single();
    }

    private String merchantToken() {
        return bearer(merchant.toString(), "MERCHANT");
    }

    private String opsToken() {
        return bearer(UUID.randomUUID().toString(), "OPS");
    }

    private String warehouseToken() {
        return bearer(UUID.randomUUID().toString(), "WAREHOUSE");
    }

    private String opsWarehouseToken() {
        return bearer(UUID.randomUUID().toString(), "OPS");
    }

    private static String bearer(String subject, String... roles) {
        return "Bearer " + TestJwts.token(subject, roles);
    }
}
