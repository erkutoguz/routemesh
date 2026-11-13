"use client";

import { useCallback, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { RoleShell } from "@/components/RoleShell";
import { StatusBadge, statusLabel } from "@/components/StatusBadge";
import { newIdempotencyKey } from "@/lib/api/client";
import { listWarehouseShipments, readyShipment, receiveShipment, WAREHOUSE_STATUSES } from "@/lib/api/operations";
import { useApi } from "@/lib/api/useApi";
import { formatMinor } from "@/lib/money";
import { useCursorList } from "@/lib/useCursorList";

export default function WarehousePage() {
  return (
    <RoleShell role="WAREHOUSE">
      <WarehouseConsole />
    </RoleShell>
  );
}

function WarehouseConsole() {
  const api = useApi();
  const [status, setStatus] = useState("");
  const [statusOverride, setStatusOverride] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const fetchPage = useCallback(
    (cursor: string | null) => listWarehouseShipments(api, { status, cursor }),
    [api, status],
  );
  const { items, loading, error, hasMore, loadMore, reload } = useCursorList(fetchPage);

  async function act(id: string, action: "receive" | "ready") {
    setBusyId(id);
    setActionError(null);
    try {
      const key = newIdempotencyKey();
      const shipment =
        action === "receive" ? await receiveShipment(api, id, key) : await readyShipment(api, id, key);
      setStatusOverride((previous) => ({ ...previous, [id]: shipment.status }));
    } catch (e) {
      setActionError(e);
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Warehouse console</h1>
          <p className="text-sm text-slate-500">Receive paid shipments, then mark them ready for courier dispatch.</p>
        </div>
        <div className="flex items-center gap-2">
          <select
            aria-label="Status filter"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
            className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm"
          >
            <option value="">Waiting for the warehouse</option>
            {WAREHOUSE_STATUSES.map((s) => (
              <option key={s} value={s}>
                {statusLabel(s)}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => {
              setStatusOverride({});
              reload();
            }}
            className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100"
          >
            Refresh
          </button>
        </div>
      </div>

      <ErrorBanner error={error} />
      <ErrorBanner error={actionError} />

      <div className="overflow-x-auto rounded border border-slate-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-50 text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-2">Shipment</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2 text-right">Amount</th>
              <th className="px-4 py-2">Created</th>
              <th className="px-4 py-2 text-right">Action</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {items.map((s) => {
              const current = statusOverride[s.id] ?? s.status;
              return (
                <tr key={s.id}>
                  <td className="px-4 py-2 font-mono text-xs">{s.id.slice(0, 8)}…</td>
                  <td className="px-4 py-2">
                    <StatusBadge status={current} />
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums">{formatMinor(s.amount.amountMinor)}</td>
                  <td className="px-4 py-2 text-slate-600">{new Date(s.createdAt).toLocaleString()}</td>
                  <td className="px-4 py-2 text-right">
                    {current === "PAID" && (
                      <button
                        type="button"
                        disabled={busyId === s.id}
                        onClick={() => void act(s.id, "receive")}
                        className="rounded bg-slate-900 px-3 py-1 text-white hover:bg-slate-700 disabled:opacity-50"
                      >
                        Receive
                      </button>
                    )}
                    {current === "AT_WAREHOUSE" && (
                      <button
                        type="button"
                        disabled={busyId === s.id}
                        onClick={() => void act(s.id, "ready")}
                        className="rounded bg-emerald-700 px-3 py-1 text-white hover:bg-emerald-600 disabled:opacity-50"
                      >
                        Mark ready
                      </button>
                    )}
                    {current === "READY_FOR_DISPATCH" && <span className="text-slate-500">Waiting for a courier</span>}
                  </td>
                </tr>
              );
            })}
            {!loading && items.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-slate-500">
                  Nothing is waiting in the warehouse.
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
