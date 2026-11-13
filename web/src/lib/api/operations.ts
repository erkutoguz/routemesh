import type { ApiClient } from "./client";
import type { Point, Shipment, ShipmentPage } from "./merchant";

export const WAREHOUSE_STATUSES = ["PAID", "AT_WAREHOUSE", "READY_FOR_DISPATCH"] as const;

export function listWarehouseShipments(
  api: ApiClient,
  params: { status?: string; cursor?: string | null; limit?: number },
): Promise<ShipmentPage> {
  const query = new URLSearchParams();
  if (params.status) {
    query.append("status", params.status);
  }
  if (params.cursor) {
    query.set("cursor", params.cursor);
  }
  query.set("limit", String(params.limit ?? 20));
  return api.get<ShipmentPage>(`/api/v1/warehouse/shipments?${query}`);
}

export function receiveShipment(api: ApiClient, id: string, idempotencyKey: string): Promise<Shipment> {
  return api.post<Shipment>(`/api/v1/warehouse/shipments/${id}/receive`, { idempotencyKey });
}

export function readyShipment(api: ApiClient, id: string, idempotencyKey: string): Promise<Shipment> {
  return api.post<Shipment>(`/api/v1/warehouse/shipments/${id}/ready`, { idempotencyKey });
}

export interface CourierProfile {
  courierId: string;
  status: string;
  maxWeightGrams: number;
  activeShipmentId: string | null;
}

export interface Offer {
  offerId: string;
  shipmentId: string;
  status: string;
  weightGrams: number;
  expiresAt: string;
  createdAt: string;
}

export interface PodFile {
  fileId: string;
  kind: "PHOTO" | "SIGNATURE";
}

export interface DeliverInput {
  photoFileId: string;
  signatureFileId?: string;
  latitude: number;
  longitude: number;
  accuracyMeters: number;
  recipientName: string;
  otp: string;
}

export const getCourier = (api: ApiClient) => api.get<CourierProfile>("/api/v1/fleet/me");

export const setCourierStatus = (api: ApiClient, status: "IDLE" | "OFFLINE") =>
  api.put<CourierProfile>("/api/v1/fleet/me/status", { body: { status } });

export const listOffers = (api: ApiClient) => api.get<Offer[]>("/api/v1/fleet/me/offers");

export const acceptOffer = (api: ApiClient, offerId: string) =>
  api.post<Offer>(`/api/v1/fleet/offers/${offerId}/accept`);

export const rejectOffer = (api: ApiClient, offerId: string) =>
  api.post<Offer>(`/api/v1/fleet/offers/${offerId}/reject`);

export interface PingSession {
  epoch: number;
  sequence: number;
}

export function newPingSession(): PingSession {
  return { epoch: Date.now(), sequence: 0 };
}

export async function sendPing(api: ApiClient, session: PingSession, point: Point): Promise<{ accepted: boolean }> {
  session.sequence += 1;
  return api.post<{ accepted: boolean }>("/api/v1/couriers/me/location", {
    body: {
      sessionEpoch: session.epoch,
      sequenceNumber: session.sequence,
      latitude: point.latitude,
      longitude: point.longitude,
    },
  });
}

export const departShipment = (api: ApiClient, shipmentId: string, idempotencyKey: string) =>
  api.post<Shipment>(`/api/v1/shipments/${shipmentId}/transitions`, {
    body: { toStatus: "OUT_FOR_DELIVERY" },
    idempotencyKey,
  });

export function uploadPodFile(api: ApiClient, shipmentId: string, kind: PodFile["kind"], file: Blob, name: string) {
  const form = new FormData();
  form.set("file", file, name);
  return api.post<PodFile>(`/api/v1/shipments/${shipmentId}/pod/files?kind=${kind}`, { body: form });
}

export const deliverShipment = (api: ApiClient, shipmentId: string, input: DeliverInput, idempotencyKey: string) =>
  api.post<Shipment>(`/api/v1/shipments/${shipmentId}/deliver`, { body: input, idempotencyKey });
