import { describe, expect, it } from "vitest";
import { approachText, isTerminalEnd, parseTrackingEvent } from "./tracking";

const msg = (event: string, data: unknown) => ({ event, data: JSON.stringify(data), id: null });

describe("parseTrackingEvent", () => {
  it("parses a location event", () => {
    const parsed = parseTrackingEvent(
      msg("location", { latitude: 41.01, longitude: 28.97, target: "DELIVERY", distanceMeters: 812.3, timestamp: "2026-11-13T10:00:00Z" }),
    );
    expect(parsed).toEqual({
      type: "location",
      location: { latitude: 41.01, longitude: 28.97, target: "DELIVERY", distanceMeters: 812.3, timestamp: "2026-11-13T10:00:00Z" },
    });
  });

  it("parses approaching and end events", () => {
    expect(parseTrackingEvent(msg("approaching", { kind: "PICKUP", distanceMeters: 87 }))).toEqual({
      type: "approaching",
      kind: "PICKUP",
      distanceMeters: 87,
    });
    expect(parseTrackingEvent(msg("end", { reason: "TIMEOUT" }))).toEqual({ type: "end", reason: "TIMEOUT" });
  });

  it("ignores malformed and unknown events", () => {
    expect(parseTrackingEvent({ event: "location", data: "not json", id: null })).toBeNull();
    expect(parseTrackingEvent(msg("location", { latitude: "x" }))).toBeNull();
    expect(parseTrackingEvent(msg("mystery", {}))).toBeNull();
  });
});

describe("isTerminalEnd", () => {
  it("is terminal only when the shipment tracking ENDED", () => {
    expect(isTerminalEnd(msg("end", { reason: "ENDED" }))).toBe(true);
    expect(isTerminalEnd(msg("end", { reason: "TOKEN_EXPIRED" }))).toBe(false);
    expect(isTerminalEnd(msg("location", {}))).toBe(false);
  });
});

describe("approachText", () => {
  it("names the target and rounds the distance", () => {
    expect(approachText("DELIVERY", 88.6)).toBe("Courier is approaching the drop-off point: 89 m away");
  });
});
