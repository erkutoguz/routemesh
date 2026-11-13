import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "./client";
import {
  deliverShipment,
  departShipment,
  newPingSession,
  receiveShipment,
  sendPing,
  uploadPodFile,
} from "./operations";

function setup() {
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () => new Response(JSON.stringify({ accepted: true })));
  const api = createApiClient({ getAccessToken: async () => "t", refresh: async () => null, baseUrl: "http://gw", fetchImpl });
  return { api, fetchImpl };
}

describe("operations api", () => {
  it("increments the ping sequence within one epoch", async () => {
    const { api, fetchImpl } = setup();
    const session = newPingSession();

    await sendPing(api, session, { latitude: 41, longitude: 29 });
    await sendPing(api, session, { latitude: 41.1, longitude: 29.1 });

    const bodies = fetchImpl.mock.calls.map((c) => JSON.parse(c[1]!.body as string));
    expect(bodies.map((b) => b.sequenceNumber)).toEqual([1, 2]);
    expect(new Set(bodies.map((b) => b.sessionEpoch)).size).toBe(1);
    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/couriers/me/location");
  });

  it("starts a newer epoch for a new session", async () => {
    const first = newPingSession();
    await new Promise((r) => setTimeout(r, 2));
    expect(newPingSession().epoch).toBeGreaterThan(first.epoch);
  });

  it("sends receive with an idempotency key and no body", async () => {
    const { api, fetchImpl } = setup();

    await receiveShipment(api, "s1", "k1");

    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/warehouse/shipments/s1/receive");
    expect((fetchImpl.mock.calls[0][1]!.headers as Headers).get("Idempotency-Key")).toBe("k1");
  });

  it("departs through the generic transition endpoint", async () => {
    const { api, fetchImpl } = setup();

    await departShipment(api, "s1", "k2");

    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/shipments/s1/transitions");
    expect(JSON.parse(fetchImpl.mock.calls[0][1]!.body as string)).toEqual({ toStatus: "OUT_FOR_DELIVERY" });
  });

  it("uploads proof files as multipart with the kind in the query", async () => {
    const { api, fetchImpl } = setup();

    await uploadPodFile(api, "s1", "PHOTO", new Blob(["x"], { type: "image/png" }), "photo.png");

    expect(fetchImpl.mock.calls[0][0]).toBe("http://gw/api/v1/shipments/s1/pod/files?kind=PHOTO");
    const body = fetchImpl.mock.calls[0][1]!.body as FormData;
    expect((body.get("file") as File).name).toBe("photo.png");
    expect((fetchImpl.mock.calls[0][1]!.headers as Headers).get("Content-Type")).toBeNull();
  });

  it("delivers with the otp and idempotency key", async () => {
    const { api, fetchImpl } = setup();

    await deliverShipment(
      api,
      "s1",
      { photoFileId: "p", latitude: 1, longitude: 2, accuracyMeters: 5, recipientName: "Ada", otp: "123456" },
      "k3",
    );

    expect(JSON.parse(fetchImpl.mock.calls[0][1]!.body as string).otp).toBe("123456");
    expect((fetchImpl.mock.calls[0][1]!.headers as Headers).get("Idempotency-Key")).toBe("k3");
  });
});
