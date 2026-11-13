import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "./client";
import { createQuote, listShipments, listWalletTransactions, pspPaymentId } from "./merchant";

function client(body: unknown) {
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => new Response(JSON.stringify(body)));
  const api = createApiClient({
    getAccessToken: async () => "t",
    refresh: async () => null,
    baseUrl: "http://gw",
    fetchImpl,
  });
  return { api, fetchImpl };
}

describe("merchant api", () => {
  it("builds the shipment search query with status, cursor and limit", async () => {
    const { api, fetchImpl } = client({ items: [], nextCursor: null });

    await listShipments(api, { status: "PAID", cursor: "abc=", limit: 10 });

    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/search/shipments?status=PAID&cursor=abc%3D&limit=10");
  });

  it("omits empty filters", async () => {
    const { api, fetchImpl } = client({ items: [], nextCursor: null });

    await listShipments(api, {});

    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/search/shipments?limit=20");
  });

  it("passes the numeric wallet cursor", async () => {
    const { api, fetchImpl } = client({ items: [], nextCursor: null });

    await listWalletTransactions(api, { cursor: 42 });

    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/wallets/me/transactions?limit=20&cursor=42");
  });

  it("sends the quote with an idempotency key", async () => {
    const { api, fetchImpl } = client({ id: "q" });

    await createQuote(
      api,
      {
        origin: { latitude: 1, longitude: 2 },
        destination: { latitude: 3, longitude: 4 },
        serviceLevel: "EXPRESS",
        weightGrams: 500,
      },
      "key-1",
    );

    const headers = fetchImpl.mock.calls[0][1]!.headers as Headers;
    expect(headers.get("Idempotency-Key")).toBe("key-1");
  });

  it("extracts the psp payment id from the checkout url", () => {
    expect(pspPaymentId("http://localhost:8085/checkout/psp_123")).toBe("psp_123");
    expect(pspPaymentId("http://localhost:8085/checkout/psp_123/")).toBe("psp_123");
  });
});
