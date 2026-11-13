import type { ApiClient } from "./client";

export type ServiceLevel = "STANDARD" | "EXPRESS";

export interface Money {
  amountMinor: number;
  currency: string;
}

export interface Point {
  latitude: number;
  longitude: number;
}

export interface Quote {
  id: string;
  origin: Point;
  destination: Point;
  zoneCode: string;
  serviceLevel: ServiceLevel;
  weightGrams: number;
  distanceMeters: number;
  basePrice: Money;
  surgeBps: number;
  finalAmount: Money;
  slaMinutes: number;
  status: string;
  createdAt: string;
  expiresAt: string;
  replayed: boolean;
}

export interface QuoteInput {
  origin: Point;
  destination: Point;
  serviceLevel: ServiceLevel;
  weightGrams: number;
}

export interface ShipmentInput {
  quoteId: string;
  recipientName: string;
  recipientPhone: string;
  deliveryAddress: string;
}

export interface ShipmentCreated {
  shipmentId: string;
  paymentId?: string;
  checkoutUrl?: string;
  status?: string;
}

export interface Shipment {
  id: string;
  merchantId: string;
  status: string;
  courierId: string | null;
  recipientName: string;
  recipientPhone: string;
  deliveryAddress: string;
  deliveryLocation: Point;
  quoteId: string;
  finalAmount: Money;
  basePrice: Money;
  createdAt: string;
  updatedAt: string;
}

export interface ShipmentListItem {
  id: string;
  merchantId: string;
  status: string;
  courierId: string | null;
  amount: Money;
  createdAt: string;
  updatedAt: string;
  deliveredAt: string | null;
}

export interface ShipmentPage {
  items: ShipmentListItem[];
  nextCursor: string | null;
}

export interface Wallet {
  accountCode: string;
  balance: Money;
}

export interface WalletTransaction {
  entryId: number;
  transactionId: string;
  type: string;
  direction: "DEBIT" | "CREDIT";
  amountMinor: number;
  shipmentId: string | null;
  createdAt: string;
}

export interface WalletTransactionPage {
  items: WalletTransaction[];
  nextCursor: number | null;
}

export const SHIPMENT_STATUSES = [
  "PENDING_PAYMENT",
  "PAID",
  "AT_WAREHOUSE",
  "READY_FOR_DISPATCH",
  "ASSIGNED",
  "OUT_FOR_DELIVERY",
  "DELIVERED",
  "DELIVERY_FAILED",
  "RETURN_TO_WAREHOUSE",
  "DAMAGED",
  "ON_HOLD",
  "CANCELLED",
  "LOST",
] as const;

export function createQuote(api: ApiClient, input: QuoteInput, idempotencyKey: string): Promise<Quote> {
  return api.post<Quote>("/api/v1/quotes", { body: input, idempotencyKey });
}

export function getQuote(api: ApiClient, id: string): Promise<Quote> {
  return api.get<Quote>(`/api/v1/quotes/${id}`);
}

export function createShipment(api: ApiClient, input: ShipmentInput, idempotencyKey: string): Promise<ShipmentCreated> {
  return api.post<ShipmentCreated>("/api/v1/shipments", { body: input, idempotencyKey });
}

export function getShipment(api: ApiClient, id: string): Promise<Shipment> {
  return api.get<Shipment>(`/api/v1/shipments/${id}`);
}

export function listShipments(
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
  return api.get<ShipmentPage>(`/api/v1/search/shipments?${query}`);
}

export function getWallet(api: ApiClient): Promise<Wallet> {
  return api.get<Wallet>("/api/v1/wallets/me");
}

export function listWalletTransactions(
  api: ApiClient,
  params: { cursor?: number | null; limit?: number },
): Promise<WalletTransactionPage> {
  const query = new URLSearchParams({ limit: String(params.limit ?? 20) });
  if (params.cursor) {
    query.set("cursor", String(params.cursor));
  }
  return api.get<WalletTransactionPage>(`/api/v1/wallets/me/transactions?${query}`);
}

export function pspPaymentId(checkoutUrl: string): string {
  return checkoutUrl.split("/").filter(Boolean).at(-1) ?? "";
}
