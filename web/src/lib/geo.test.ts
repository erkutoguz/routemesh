import { describe, expect, it } from "vitest";
import { DEPOT, distanceMeters, offsetPoint, POD_RADIUS_METERS } from "./geo";

const DROP = { latitude: 40.9906, longitude: 29.029 };

describe("geo", () => {
  it("measures a known distance", () => {
    expect(distanceMeters({ latitude: 41, longitude: 29 }, { latitude: 41.01, longitude: 29 })).toBeCloseTo(1112, -1);
    expect(distanceMeters(DROP, DROP)).toBe(0);
  });

  it("offsets a point by the requested distance", () => {
    for (const meters of [15, 120, 5000]) {
      expect(distanceMeters(DROP, offsetPoint(DROP, meters, 45))).toBeCloseTo(meters, 0);
    }
  });

  it("puts 120 m outside and 15 m inside the 50 m geofence", () => {
    expect(distanceMeters(DROP, offsetPoint(DROP, 120, 200))).toBeGreaterThan(POD_RADIUS_METERS);
    expect(distanceMeters(DROP, offsetPoint(DROP, 15, 200))).toBeLessThan(POD_RADIUS_METERS);
  });

  it("keeps the depot inside Istanbul", () => {
    expect(DEPOT.latitude).toBeGreaterThan(40.8);
    expect(DEPOT.longitude).toBeLessThan(29.5);
  });
});
