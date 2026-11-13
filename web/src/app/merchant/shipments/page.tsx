"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { StatusBadge, statusLabel } from "@/components/StatusBadge";
import { listShipments, SHIPMENT_STATUSES } from "@/lib/api/merchant";
import { useApi } from "@/lib/api/useApi";
import { formatMinor } from "@/lib/money";
import { useCursorList } from "@/lib/useCursorList";

export default function ShipmentsPage() {
  const api = useApi();
  const [status, setStatus] = useState("");
  const fetchPage = useCallback((cursor: string | null) => listShipments(api, { status, cursor }), [api, status]);
  const { items, loading, error, hasMore, loadMore, reload } = useCursorList(fetchPage);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">Shipments</h1>
        <div className="flex items-center gap-2">
          <select
            aria-label="Status filter"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm"
          >
            <option value="">All statuses</option>
            {SHIPMENT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {statusLabel(s)}
              </option>
            ))}
          </select>
          <button type="button" onClick={reload} className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100">
            Refresh
          </button>
        </div>
      </div>

      <ErrorBanner error={error} />

      <div className="overflow-x-auto rounded border border-slate-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-50 text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-2">Shipment</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2 text-right">Amount</th>
              <th className="px-4 py-2">Created</th>
              <th className="px-4 py-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((s) => (
              <tr key={s.id}>
                <td className="px-4 py-2 font-mono text-xs">{s.id.slice(0, 8)}…</td>
                <td className="px-4 py-2">
                  <StatusBadge status={s.status} />
                </td>
                <td className="px-4 py-2 text-right tabular-nums">{formatMinor(s.amount.amountMinor)}</td>
                <td className="px-4 py-2 text-slate-600">{new Date(s.createdAt).toLocaleString()}</td>
                <td className="px-4 py-2 text-right">
                  <Link href={`/merchant/shipments/${s.id}`} className="font-medium underline">
                    Open
                  </Link>
                </td>
              </tr>
            ))}
            {!loading && items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-slate-500">
                  No shipments{status ? ` with status ${statusLabel(status)}` : ""} yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {loading && <p className="text-sm text-slate-500">Loading…</p>}
      {hasMore && !loading && (
        <button type="button" onClick={loadMore} className="rounded border border-slate-300 px-4 py-2 text-sm hover:bg-slate-100">
          Load more
        </button>
      )}
    </div>
  );
}
