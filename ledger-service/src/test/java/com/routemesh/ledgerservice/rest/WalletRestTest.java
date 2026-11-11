package com.routemesh.ledgerservice.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.routemesh.ledgerservice.TestcontainersConfiguration;
import com.routemesh.ledgerservice.domain.Direction;
import com.routemesh.ledgerservice.domain.EntryCommand;
import com.routemesh.ledgerservice.domain.PostTransactionCommand;
import com.routemesh.ledgerservice.service.LedgerService;
import com.routemesh.platform.security.test.TestJwtConfiguration;
import com.routemesh.platform.security.test.TestJwts;

@SpringBootTest(properties = "spring.grpc.server.port=0")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestJwtConfiguration.class})
class WalletRestTest {

    private static final String EXPENSE = "EXPENSE:LOST_COMPENSATION";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LedgerService ledger;

    // --- güvenlik ---

    @Test
    void withoutTokenBothEndpointsAreUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/wallets/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/wallets/me/transactions")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyMerchantsAndCouriersHaveAWallet() throws Exception {
        for (String role : new String[] {"OPS", "WAREHOUSE", "WORKFLOW"}) {
            for (String path : new String[] {"/api/v1/wallets/me", "/api/v1/wallets/me/transactions"}) {
                mvc.perform(get(path).header("Authorization", bearer(UUID.randomUUID().toString(), role)))
                        .andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void aTokenCarryingBothWalletRolesOrANonUuidSubjectIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/wallets/me")
                        .header("Authorization", bearer(UUID.randomUUID().toString(), "MERCHANT", "COURIER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer("not-a-uuid", "MERCHANT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/wallets/me/transactions").header("Authorization", bearer("not-a-uuid", "COURIER")))
                .andExpect(status().isForbidden());
    }

    // --- bakiye ---

    @Test
    void aWalletThatDoesNotExistYetHasAZeroBalance() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID courier = UUID.randomUUID();

        mvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer(merchant.toString(), "MERCHANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCode").value("LIABILITY:MERCHANT_WALLET:" + merchant))
                .andExpect(jsonPath("$.balance.amountMinor").value(0))
                .andExpect(jsonPath("$.balance.currency").value("TRY"));
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer(courier.toString(), "COURIER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCode").value("LIABILITY:COURIER_WALLET:" + courier))
                .andExpect(jsonPath("$.balance.amountMinor").value(0));
        mvc.perform(get("/api/v1/wallets/me/transactions")
                        .header("Authorization", bearer(merchant.toString(), "MERCHANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void theBalanceIsCreditsMinusDebits() throws Exception {
        UUID merchant = UUID.randomUUID();
        credit(merchant, 750, null, "DELIVERY_SETTLEMENT");
        credit(merchant, 300, null, "DELIVERY_SETTLEMENT");
        debit(merchant, 100, "PAYOUT");

        mvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer(merchant.toString(), "MERCHANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.amountMinor").value(950));
    }

    @Test
    void aMerchantAndACourierWithTheSameIdHaveSeparateWallets() throws Exception {
        UUID id = UUID.randomUUID();
        credit(id, 500, null, "DELIVERY_SETTLEMENT");

        mvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer(id.toString(), "MERCHANT")))
                .andExpect(jsonPath("$.balance.amountMinor").value(500));
        mvc.perform(get("/api/v1/wallets/me").header("Authorization", bearer(id.toString(), "COURIER")))
                .andExpect(jsonPath("$.balance.amountMinor").value(0));
    }

    // --- işlem geçmişi ---

    @Test
    void transactionsListNewestFirstWithAllFields() throws Exception {
        UUID merchant = UUID.randomUUID();
        UUID shipment = UUID.randomUUID();
        credit(merchant, 750, shipment, "DELIVERY_SETTLEMENT");
        debit(merchant, 100, "PAYOUT");

        mvc.perform(get("/api/v1/wallets/me/transactions")
                        .header("Authorization", bearer(merchant.toString(), "MERCHANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].type").value("PAYOUT"))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].amountMinor").value(100))
                .andExpect(jsonPath("$.items[0].shipmentId").doesNotExist())
                .andExpect(jsonPath("$.items[0].entryId").isNumber())
                .andExpect(jsonPath("$.items[0].transactionId").exists())
                .andExpect(jsonPath("$.items[0].createdAt").exists())
                .andExpect(jsonPath("$.items[1].type").value("DELIVERY_SETTLEMENT"))
                .andExpect(jsonPath("$.items[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.items[1].amountMinor").value(750))
                .andExpect(jsonPath("$.items[1].shipmentId").value(shipment.toString()))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void cursorPagingWalksTheWholeHistoryWithoutGapsOrRepeats() throws Exception {
        UUID owner = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            credit(owner, i * 10L, null, "DELIVERY_SETTLEMENT");
        }
        String auth = bearer(owner.toString(), "MERCHANT");

        String first = mvc.perform(get("/api/v1/wallets/me/transactions?limit=2").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amountMinor").value(50))
                .andExpect(jsonPath("$.items[1].amountMinor").value(40))
                .andExpect(jsonPath("$.nextCursor").isNumber())
                .andReturn().getResponse().getContentAsString();
        String cursor1 = first.replaceAll(".*\"nextCursor\":(\\d+).*", "$1");
        assertThat(first).contains("\"entryId\":" + cursor1);

        String second = mvc.perform(get("/api/v1/wallets/me/transactions?limit=2&cursor=" + cursor1)
                        .header("Authorization", auth))
                .andExpect(jsonPath("$.items[0].amountMinor").value(30))
                .andExpect(jsonPath("$.items[1].amountMinor").value(20))
                .andExpect(jsonPath("$.nextCursor").isNumber())
                .andReturn().getResponse().getContentAsString();
        String cursor2 = second.replaceAll(".*\"nextCursor\":(\\d+).*", "$1");

        mvc.perform(get("/api/v1/wallets/me/transactions?limit=2&cursor=" + cursor2).header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amountMinor").value(10))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void aPageThatExactlyEndsTheHistoryHasNoNextCursor() throws Exception {
        UUID owner = UUID.randomUUID();
        credit(owner, 10, null, "DELIVERY_SETTLEMENT");
        credit(owner, 20, null, "DELIVERY_SETTLEMENT");

        mvc.perform(get("/api/v1/wallets/me/transactions?limit=2")
                        .header("Authorization", bearer(owner.toString(), "MERCHANT")))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void historiesAreIsolatedPerOwner() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        credit(alice, 111, null, "DELIVERY_SETTLEMENT");
        credit(bob, 222, null, "DELIVERY_SETTLEMENT");

        mvc.perform(get("/api/v1/wallets/me/transactions").header("Authorization", bearer(alice.toString(), "MERCHANT")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amountMinor").value(111));
        mvc.perform(get("/api/v1/wallets/me/transactions").header("Authorization", bearer(bob.toString(), "MERCHANT")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amountMinor").value(222));
    }

    @Test
    void aCursorFromAnotherWalletCannotLeakEntries() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        credit(alice, 1, null, "DELIVERY_SETTLEMENT");
        credit(bob, 2, null, "DELIVERY_SETTLEMENT");

        mvc.perform(get("/api/v1/wallets/me/transactions?cursor=9223372036854775807")
                        .header("Authorization", bearer(alice.toString(), "MERCHANT")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].amountMinor").value(1));
    }

    @Test
    void paginationParametersAreValidated() throws Exception {
        String auth = bearer(UUID.randomUUID().toString(), "MERCHANT");
        for (String query : List.of("limit=0", "limit=101", "limit=-3", "limit=abc", "cursor=0", "cursor=-5",
                "cursor=abc")) {
            mvc.perform(get("/api/v1/wallets/me/transactions?" + query).header("Authorization", auth))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/wallets/me/transactions?limit=100").header("Authorization", auth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/wallets/me/transactions?limit=1").header("Authorization", auth))
                .andExpect(status().isOk());
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/wallets/me/transactions?limit=0")
                        .header("Authorization", bearer(UUID.randomUUID().toString(), "MERCHANT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("limit must be between 1 and 100"));
    }

    // --- yardımcılar ---

    private void credit(UUID owner, long amountMinor, UUID shipment, String type) {
        post(type, shipment, List.of(
                new EntryCommand(EXPENSE, Direction.DEBIT, amountMinor, "TRY"),
                new EntryCommand(walletOf(owner), Direction.CREDIT, amountMinor, "TRY")));
    }

    private void debit(UUID owner, long amountMinor, String type) {
        post(type, null, List.of(
                new EntryCommand(walletOf(owner), Direction.DEBIT, amountMinor, "TRY"),
                new EntryCommand("ASSET:PSP_CLEARING", Direction.CREDIT, amountMinor, "TRY")));
    }

    private void post(String type, UUID shipment, List<EntryCommand> entries) {
        ledger.post(new PostTransactionCommand(UUID.randomUUID().toString(), type, shipment, null, entries));
    }

    private static String walletOf(UUID owner) {
        return "LIABILITY:MERCHANT_WALLET:" + owner;
    }

    private static String bearer(String subject, String... roles) {
        return "Bearer " + TestJwts.token(subject, roles);
    }
}
