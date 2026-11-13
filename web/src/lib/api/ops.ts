import type { ApiClient } from "./client";

export const RECONCILIATION_FINDING_TYPES = [
  "MISSING_AT_PSP",
  "UNKNOWN_AT_PLATFORM",
  "STATUS_MISMATCH",
  "CAPTURED_AMOUNT_MISMATCH",
  "REFUNDED_AMOUNT_MISMATCH",
  "STUCK_TRANSACTION",
  "PSP_REPORT_UNAVAILABLE",
  "CLEARING_MISMATCH",
  "LEDGER_UNAVAILABLE",
  "TRUNCATED",
] as const;

export const SEVERITIES = ["CRITICAL", "WARNING"] as const;

export const INTEGRITY_FINDING_TYPES = [
  "UNBALANCED_TRANSACTION",
  "TOTALS_MISMATCH",
  "NEGATIVE_BALANCE",
  "REVERSAL_MISMATCH",
  "HISTORY_TAMPERED",
  "CACHE_DRIFT",
  "TRUNCATED",
] as const;

export interface ReconciliationRun {
  id: string;
  trigger: string;
  requestedBy: string | null;
  status: string;
  startedAt: string;
  finishedAt: string | null;
  windowFrom: string | null;
  windowTo: string | null;
  pspPaymentsSeen: number;
  intentsChecked: number;
  findingsCount: number;
  error: string | null;
}

export interface ReconciliationSummary {
  status: string;
  latestRun: ReconciliationRun | null;
  activeRunId: string | null;
  lastCompletedWindowEnd: string | null;
  findingsByType: Record<string, number>;
  findingsBySeverity: Record<string, number>;
}

export interface ReconciliationFinding {
  id: number;
  runId: string;
  type: string;
  severity: string;
  intentId: string | null;
  shipmentId: string | null;
  pspPaymentId: string | null;
  localStatus: string | null;
  pspStatus: string | null;
  localCapturedMinor: number | null;
  pspCapturedMinor: number | null;
  detail: unknown;
  createdAt: string;
}

export interface FindingPage<T> {
  items: T[];
  nextCursor: string | null;
}

export interface IntegrityRun {
  id: string;
  mode: string;
  trigger: string;
  requestedBy: string | null;
  status: string;
  startedAt: string;
  finishedAt: string | null;
  entriesScanned: number;
  findingsCount: number;
  error: string | null;
}

export interface IntegrityCheckpoint {
  id: number;
  takenAt: string;
  lastEntryId: number;
  entryCount: number;
  totalDebitMinor: number;
  totalCreditMinor: number;
  chainHash: string;
}

export interface IntegritySummary {
  status: string;
  latestIncremental: IntegrityRun | null;
  latestFull: IntegrityRun | null;
  latestCheckpoint: IntegrityCheckpoint | null;
  negativeBalances: { accountCode: string; shipmentId: string | null; netDebitMinor: number }[];
}

export interface IntegrityFinding {
  id: number;
  runId: string;
  type: string;
  severity: string;
  accountCode: string | null;
  shipmentId: string | null;
  expectedMinor: number | null;
  actualMinor: number | null;
  detail: unknown;
  createdAt: string;
}

export const getReconciliation = (api: ApiClient) =>
  api.get<ReconciliationSummary>("/api/v1/ops/payments/reconciliation");

export const startReconciliation = (api: ApiClient) =>
  api.post<ReconciliationRun>("/api/v1/ops/payments/reconciliation-runs");

export function listReconciliationFindings(
  api: ApiClient,
  params: { type?: string; severity?: string; cursor?: string | null; limit?: number },
) {
  const query = new URLSearchParams();
  if (params.type) {
    query.set("type", params.type);
  }
  if (params.severity) {
    query.set("severity", params.severity);
  }
  if (params.cursor) {
    query.set("cursor", params.cursor);
  }
  query.set("limit", String(params.limit ?? 20));
  return api.get<FindingPage<ReconciliationFinding>>(`/api/v1/ops/payments/reconciliation-findings?${query}`);
}

export const getIntegrity = (api: ApiClient) => api.get<IntegritySummary>("/api/v1/ops/ledger/integrity");

export const startIntegrityRun = (api: ApiClient, mode: "INCREMENTAL" | "FULL") =>
  api.post<IntegrityRun>("/api/v1/ops/ledger/integrity-runs", { body: { mode } });

export function listIntegrityFindings(api: ApiClient, params: { type?: string; cursor?: string | null; limit?: number }) {
  const query = new URLSearchParams();
  if (params.type) {
    query.set("type", params.type);
  }
  if (params.cursor) {
    query.set("cursor", params.cursor);
  }
  query.set("limit", String(params.limit ?? 20));
  return api.get<FindingPage<IntegrityFinding>>(`/api/v1/ops/ledger/findings?${query}`);
}

export function summaryTone(status: string): "ok" | "warn" | "bad" | "unknown" {
  if (status === "OK" || status === "HEALTHY") {
    return "ok";
  }
  if (status === "DISCREPANCIES" || status === "DEGRADED") {
    return "warn";
  }
  if (status === "VIOLATIONS") {
    return "bad";
  }
  return "unknown";
}
