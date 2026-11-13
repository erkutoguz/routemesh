"use client";

import { useCallback, useState } from "react";
import {
  getReconciliation,
  listReconciliationFindings,
  RECONCILIATION_FINDING_TYPES,
  SEVERITIES,
  startReconciliation,
} from "@/lib/api/ops";
import { useApi } from "@/lib/api/useApi";
import { formatMinor } from "@/lib/money";
import { usePoll } from "@/lib/usePoll";
import { useCursorList } from "@/lib/useCursorList";
import { ErrorBanner } from "./ErrorBanner";
import { Stat, StatusPill } from "./StatusPill";

const SELECT = "rounded border border-slate-300 bg-white px-3 py-1.5 text-sm";

export function ReconciliationPanel() {
  const api = useApi();
  const [type, setType] = useState("");
  const [severity, setSeverity] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [starting, setStarting] = useState(false);

  const fetchSummary = useCallback(() => getReconciliation(api), [api]);
  const summary = usePoll(fetchSummary, 4000);
  const latestId = summary.data?.latestRun?.id ?? "none";

  const fetchPage = useCallback(
    (cursor: string | null) => {
      void latestId;
      return listReconciliationFindings(api, { type, severity, cursor });
    },
    [api, type, severity, latestId],
  );
  const findings = useCursorList(fetchPage);

  async function trigger() {
    setStarting(true);
    setActionError(null);
    setNotice(null);
    try {
      const run = await startReconciliation(api);
      setNotice(`Run ${run.id.slice(0, 8)}… accepted (202), status ${run.status}. The summary refreshes while it runs.`);
      summary.refresh();
    } catch (e) {
      setActionError(e);
    } finally {
      setStarting(false);
    }
  }

  const s = summary.data;
  const running = s?.activeRunId != null;

  return (
    <div className="space-y-5">
      <section className="space-y-4 rounded border border-slate-200 bg-white p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-semibold">Three-way reconciliation</h2>
            {s && <StatusPill status={s.status} />}
            {running && <span className="text-sm text-amber-700">run in progress…</span>}
          </div>
          <button
            type="button"
            disabled={starting || running}
            onClick={() => void trigger()}
            className="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
          >
            {starting ? "Starting…" : "Run reconciliation now"}
          </button>
        </div>
        <p className="text-sm text-slate-500">
          Compares the platform, the PSP and the ledger. It reports differences and never corrects them.
        </p>
        {notice && <p className="rounded border border-sky-200 bg-sky-50 px-3 py-2 text-sm text-sky-900">{notice}</p>}
        <ErrorBanner error={actionError ?? summary.error} />
        {s?.latestRun ? (
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="Last run" value={new Date(s.latestRun.startedAt).toLocaleTimeString()} />
            <Stat label="PSP payments seen" value={s.latestRun.pspPaymentsSeen} />
            <Stat label="Intents checked" value={s.latestRun.intentsChecked} />
            <Stat label="Findings" value={s.latestRun.findingsCount} />
          </dl>
        ) : (
          <p className="text-sm text-slate-500">No completed run yet.</p>
        )}
        {s && Object.keys(s.findingsByType).length > 0 && (
          <ul className="flex flex-wrap gap-2 text-xs">
            {Object.entries(s.findingsByType).map(([name, count]) => (
              <li key={name} className="rounded bg-slate-100 px-2 py-1 font-mono">
                {name} × {count}
              </li>
            ))}
          </ul>
        )}
        {s?.latestRun?.error && <p className="text-sm text-red-700">Run error: {s.latestRun.error}</p>}
      </section>

      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h3 className="text-base font-semibold">Findings</h3>
          <div className="flex gap-2">
            <select aria-label="Finding type" value={type} onChange={(e) => setType(e.target.value)} className={SELECT}>
              <option value="">All types</option>
              {RECONCILIATION_FINDING_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
            <select aria-label="Severity" value={severity} onChange={(e) => setSeverity(e.target.value)} className={SELECT}>
              <option value="">All severities</option>
              {SEVERITIES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>
        </div>
        <ErrorBanner error={findings.error} />
        <div className="overflow-x-auto rounded border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase text-slate-500">
              <tr>
                <th className="px-4 py-2">Type</th>
                <th className="px-4 py-2">Severity</th>
                <th className="px-4 py-2">Shipment</th>
                <th className="px-4 py-2">Local / PSP</th>
                <th className="px-4 py-2 text-right">Captured (local / PSP)</th>
                <th className="px-4 py-2">Detail</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {findings.items.map((f) => (
                <tr key={f.id} className="align-top">
                  <td className="px-4 py-2 font-mono text-xs">{f.type}</td>
                  <td className="px-4 py-2">
                    <StatusPill status={f.severity === "CRITICAL" ? "VIOLATIONS" : "DEGRADED"} />
                  </td>
                  <td className="px-4 py-2 font-mono text-xs">{f.shipmentId ? `${f.shipmentId.slice(0, 8)}…` : "—"}</td>
                  <td className="px-4 py-2 text-xs">
                    {f.localStatus ?? "—"} / {f.pspStatus ?? "—"}
                  </td>
                  <td className="px-4 py-2 text-right text-xs tabular-nums">
                    {f.localCapturedMinor === null ? "—" : formatMinor(f.localCapturedMinor)} /{" "}
                    {f.pspCapturedMinor === null ? "—" : formatMinor(f.pspCapturedMinor)}
                  </td>
                  <td className="px-4 py-2">
                    <details>
                      <summary className="cursor-pointer text-xs underline">view</summary>
                      <pre className="mt-1 max-w-xs overflow-x-auto text-xs">{JSON.stringify(f.detail, null, 2)}</pre>
                    </details>
                  </td>
                </tr>
              ))}
              {!findings.loading && findings.items.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-8 text-center text-slate-500">
                    No findings.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        {findings.loading && <p className="text-sm text-slate-500">Loading…</p>}
        {findings.hasMore && !findings.loading && (
          <button type="button" onClick={findings.loadMore} className="rounded border border-slate-300 px-4 py-2 text-sm hover:bg-slate-100">
            Load more
          </button>
        )}
      </section>
    </div>
  );
}
