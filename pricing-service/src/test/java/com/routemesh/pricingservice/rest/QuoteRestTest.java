package com.routemesh.pricingservice.rest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import com.routemesh.pricingservice.TestcontainersConfiguration;

@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class QuoteRestTest {

    private static final String BODY = """
            {"origin":{"latitude":41.0136,"longitude":28.9550},
             "destination":{"latitude":40.9876,"longitude":29.0369},
             "serviceLevel":"EXPRESS","weightGrams":2500}""";

    @Autowired
    private MockMvc mvc;

    @Test
    void requestsWithoutTokenAreUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/quotes/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyMerchantsMayUseTheQuoteApi() throws Exception {
        for (String role : new String[] {"COURIER", "WAREHOUSE", "OPS", "WORKFLOW"}) {
            create(UUID.randomUUID().toString(), role, UUID.randomUUID().toString(), BODY)
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void merchantCreatesAndReadsItsQuote() throws Exception {
        String merchant = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();

        String id = jsonId(create(merchant, "MERCHANT", key, BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.zoneCode").value("IST_ASIA_CORE"))
                .andExpect(jsonPath("$.serviceLevel").value("EXPRESS"))
                .andExpect(jsonPath("$.slaMinutes").value(120))
                .andExpect(jsonPath("$.finalAmount.currency").value("TRY"))
                .andExpect(jsonPath("$.finalAmount.amountMinor").isNumber())
                .andExpect(jsonPath("$.status").value("ACTIVE")));

        create(merchant, "MERCHANT", key, BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/v1/quotes/" + id).header("Authorization", bearer(merchant, "MERCHANT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void anotherMerchantCannotSeeTheQuote() throws Exception {
        String owner = UUID.randomUUID().toString();
        String id = jsonId(create(owner, "MERCHANT", UUID.randomUUID().toString(), BODY).andExpect(status().isOk()));

        mvc.perform(get("/api/v1/quotes/" + id)
                .header("Authorization", bearer(UUID.randomUUID().toString(), "MERCHANT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestsAreMappedToProblemDetails() throws Exception {
        String merchant = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();

        create(merchant, "MERCHANT", null, BODY).andExpect(status().isBadRequest());
        create(merchant, "MERCHANT", key, "{}").andExpect(status().isBadRequest());
        create(merchant, "MERCHANT", key, "not json").andExpect(status().isBadRequest());
        create(merchant, "MERCHANT", key, BODY.replace("2500", "0")).andExpect(status().isBadRequest());
        create(merchant, "MERCHANT", key, BODY.replace("40.9876", "10.0").replace("29.0369", "10.0"))
                .andExpect(status().isUnprocessableEntity());
        create("not-a-uuid", "MERCHANT", key, BODY).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/quotes/" + UUID.randomUUID()).header("Authorization", bearer(merchant, "MERCHANT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameKeyWithDifferentContentConflicts() throws Exception {
        String merchant = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();

        create(merchant, "MERCHANT", key, BODY).andExpect(status().isOk());
        create(merchant, "MERCHANT", key, BODY.replace("2500", "3000")).andExpect(status().isConflict());
    }

    private ResultActions create(String subject, String role, String key, String body) throws Exception {
        var request = post("/api/v1/quotes").header("Authorization", bearer(subject, role))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private static String jsonId(ResultActions actions) throws Exception {
        String json = actions.andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.id");
    }

    private static String bearer(String subject, String role) {
        return "Bearer " + TestJwts.token(subject, role);
    }
}
