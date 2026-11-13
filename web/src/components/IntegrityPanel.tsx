"use client";

import { useCallback, useState } from "react";
import { getIntegrity, INTEGRITY_FINDING_TYPES, listIntegrityFindings, startIntegrityRun, type IntegrityRun } from "@/lib/api/ops";
import { useApi } from "@/lib/api/useApi";
import { formatMinor } from "@/lib/money";
import { usePoll } from "@/lib/usePoll";
import { useCursorList } from "@/lib/useCursorList";
import { ErrorBanner } from "./ErrorBanner";
import { Stat, StatusPill } from "./StatusPill";

function RunLine({ label, run }: { label: string; run: IntegrityRun | null }) {
  return (
    <div className="rounded border border-slate-200 p-3 text-sm">
      <p className="mb-1 text-xs uppercase text-slate-500">{label}</p>
      {run ? (
        <p>
          <StatusPill status={run.status === "VIOLATIONS" ? "VIOLATIONS" : run.status === "OK" ? "OK" : "DEGRADED"} />{" "}
          <span className="text-slate-600">
            {new Date(run.startedAt).toLocaleString()} · {run.entriesScanned} entries · {run.findingsCount} findings
          </span>
          {run.error && <span className="block text-red-700">{run.error}</span>}
        </p>
      ) : (
        <p className="text-slate-500">No run yet.</p>
      )}
    </div>
  );
}

export function IntegrityPanel() {
  const api = useApi();
  const [type, setType] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [starting, setStarting] = useState<"INCREMENTAL" | "FULL" | null>(null);

  const fetchSummary = useCallback(() => getIntegrity(api), [api]);
  const summary = usePoll(fetchSummary, 5000);
  const latestKey = `${summary.data?.latestIncremental?.id ?? "-"}:${summary.data?.latestFull?.id ?? "-"}`;

  const fetchPage = useCallback(
    (cursor: string | null) => {
      void latestKey;
      return listIntegrityFindings(api, { type, cursor });
    },
    [api, type, latestKey],
  );
  const findings = useCursorList(fetchPage);

  async function trigger(mode: "INCREMENTAL" | "FULL") {
    setStarting(mode);
    setActionError(null);
    setNotice(null);
    try {
      const run = await startIntegrityRun(api, mode);
      setNotice(
        mode === "FULL"
          ? `Full audit ${run.id.slice(0, 8)}… started in the background (202).`
          : `Incremental check finished: ${run.status}, ${run.entriesScanned} entries scanned, ${run.findingsCount} findings.`,
      );
      summary.refresh();
    } catch (e) {
      setActionError(e);
    } finally {
      setStarting(null);
    }
  }

  const s = summary.data;
  const cp = s?.latestCheckpoint;

  return (
    <div className="space-y-5">
      <section className="space-y-4 rounded border border-slate-200 bg-white p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-semibold">Ledger integrity</h2>
            {s && <StatusPill status={s.status} />}
          </div>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={starting !== null}
              onClick={() => void trigger("INCREMENTAL")}
              className="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
            >
              {starting === "INCREMENTAL" ? "Checking…" : "Check now"}
            </button>
            <button
              type="button"
              disabled={starting !== null}
              onClick={() => void trigger("FULL")}
              className="rounded border border-slate-300 px-4 py-2 text-sm hover:bg-slate-100 disabled:opacity-50"
            >
              Full audit
            </button>
          </div>
        </div>
        <p className="text-sm text-slate-500">
          Verifies balanced transactions, totals, reversals and the checkpoint hash chain of the immutable double-entry ledger.
        </p>
        {notice && <p className="rounded border border-sky-200 bg-sky-50 px-3 py-2 text-sm text-sky-900">{notice}</p>}
        <ErrorBanner error={actionError ?? summary.error} />
        <div className="grid gap-3 sm:grid-cols-2">
          <RunLine label="Latest incremental" run={s?.latestIncremental ?? null} />
          <RunLine label="Latest full audit" run={s?.latestFull ?? null} />
        </div>
        {cp ? (
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="Checkpoint" value={`#${cp.id}`} />
            <Stat label="Last entry" value={`#${cp.lastEntryId}`} />
            <Stat label="Debit total" value={formatMinor(cp.totalDebitMinor)} />
            <Stat label="Credit total" value={formatMinor(cp.totalCreditMinor)} />
          </dl>
        ) : (
          <p className="text-sm text-slate-500">No checkpoint taken yet.</p>
        )}
        {cp && (
          <p className="break-all font-mono text-xs text-slate-400">
            chain {cp.chainHash} · taken {new Date(cp.takenAt).toLocaleString()}
          </p>
        )}
        {s && s.negativeBalances.length > 0 && (
          <div className="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-900">
            <p className="font-medium">Negative liability balances</p>
            <ul>
              {s.negativeBalances.map((b) => (
                <li key={`${b.accountCode}:${b.shipmentId}`} className="font-mono text-xs">
                  {b.accountCode} {b.shipmentId ?? ""} {formatMinor(b.netDebitMinor)}
                </li>
              ))}
            </ul>
          </div>
        )}
      </section>

      <section className="space-y-3">
        <div className="flex items-center justify-between gap-3">
          <h3 className="text-base font-semibold">Findings</h3>
          <select aria-label="Finding type" value={type} onChange={(e) => setType(e.target.value)} className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm">
            <option value="">All types</option>
            {INTEGRITY_FINDING_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>
        <ErrorBanner error={findings.error} />
        <div className="overflow-x-auto rounded border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase text-slate-500">
              <tr>
                <th className="px-4 py-2">Type</th>
                <th className="px-4 py-2">Severity</th>
                <th className="px-4 py-2">Account</th>
                <th className="px-4 py-2 text-right">Expected / actual</th>
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
                  <td className="px-4 py-2 break-all font-mono text-xs">{f.accountCode ?? "—"}</td>
                  <td className="px-4 py-2 text-right text-xs tabular-nums">
                    {f.expectedMinor === null ? "—" : formatMinor(f.expectedMinor)} / {f.actualMinor === null ? "—" : formatMinor(f.actualMinor)}
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
                  <td colSpan={5} className="px-4 py-8 text-center text-slate-500">
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
