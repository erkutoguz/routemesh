import type { SseMessage } from "./sse";

export interface LocationEvent {
  latitude: number;
  longitude: number;
  target: string;
  distanceMeters: number | null;
  timestamp: string;
}

export type TrackingEvent =
  | { type: "location"; location: LocationEvent }
  | { type: "approaching"; kind: string; distanceMeters: number }
  | { type: "end"; reason: string };

export const LIVE_STATUSES = ["ASSIGNED", "OUT_FOR_DELIVERY"];

export function parseTrackingEvent(message: SseMessage): TrackingEvent | null {
  try {
    const data = JSON.parse(message.data) as Record<string, unknown>;
    if (message.event === "location" && typeof data.latitude === "number" && typeof data.longitude === "number") {
      return {
        type: "location",
        location: {
          latitude: data.latitude,
          longitude: data.longitude,
          target: String(data.target ?? ""),
          distanceMeters: typeof data.distanceMeters === "number" ? data.distanceMeters : null,
          timestamp: String(data.timestamp ?? ""),
        },
      };
    }
    if (message.event === "approaching" && typeof data.distanceMeters === "number") {
      return { type: "approaching", kind: String(data.kind ?? ""), distanceMeters: data.distanceMeters };
    }
    if (message.event === "end") {
      return { type: "end", reason: String(data.reason ?? "ENDED") };
    }
  } catch {
    return null;
  }
  return null;
}

export function isTerminalEnd(message: SseMessage): boolean {
  const event = parseTrackingEvent(message);
  return event?.type === "end" && event.reason === "ENDED";
}

export function approachText(kind: string, distanceMeters: number): string {
  const place = kind === "PICKUP" ? "the pickup point" : kind === "DELIVERY" ? "the drop-off point" : "the target";
  return `Courier is approaching ${place}: ${Math.round(distanceMeters)} m away`;
}
