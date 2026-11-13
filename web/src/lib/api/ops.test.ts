import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "./client";
import {
  listIntegrityFindings,
  listReconciliationFindings,
  startIntegrityRun,
  startReconciliation,
  summaryTone,
} from "./ops";

function setup(status = 200) {
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => new Response(JSON.stringify({ items: [], nextCursor: null }), { status }));
  const api = createApiClient({ getAccessToken: async () => "t", refresh: async () => null, baseUrl: "http://gw", fetchImpl });
  return { api, fetchImpl };
}

describe("ops api", () => {
  it("starts a manual reconciliation run without a body", async () => {
    const { api, fetchImpl } = setup(202);

    await startReconciliation(api);

    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/ops/payments/reconciliation-runs");
    expect(fetchImpl.mock.calls[0][1]!.method).toBe("POST");
    expect(fetchImpl.mock.calls[0][1]!.body).toBeUndefined();
  });

  it("filters reconciliation findings by type, severity and cursor", async () => {
    const { api, fetchImpl } = setup();

    await listReconciliationFindings(api, { type: "CLEARING_MISMATCH", severity: "CRITICAL", cursor: "42" });

    expect(fetchImpl.mock.calls[0][0]).toBe(
      "http://gw/api/v1/ops/payments/reconciliation-findings?type=CLEARING_MISMATCH&severity=CRITICAL&cursor=42&limit=20",
    );
  });

  it("starts an integrity run with the chosen mode", async () => {
    const { api, fetchImpl } = setup();

    await startIntegrityRun(api, "FULL");

    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/ops/ledger/integrity-runs");
    expect(JSON.parse(fetchImpl.mock.calls[0][1]!.body as string)).toEqual({ mode: "FULL" });
  });

  it("lists integrity findings from the ledger endpoint", async () => {
    const { api, fetchImpl } = setup();

    await listIntegrityFindings(api, { type: "CACHE_DRIFT" });

    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/ops/ledger/findings?type=CACHE_DRIFT&limit=20");
  });
});

describe("summaryTone", () => {
  it("maps backend statuses to tones", () => {
    expect(summaryTone("OK")).toBe("ok");
    expect(summaryTone("HEALTHY")).toBe("ok");
    expect(summaryTone("DISCREPANCIES")).toBe("warn");
    expect(summaryTone("DEGRADED")).toBe("warn");
    expect(summaryTone("VIOLATIONS")).toBe("bad");
    expect(summaryTone("UNKNOWN")).toBe("unknown");
  });
});
