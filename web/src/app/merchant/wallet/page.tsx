"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { getWallet, listWalletTransactions, type Wallet } from "@/lib/api/merchant";
import { useApi } from "@/lib/api/useApi";
import { formatMinor } from "@/lib/money";
import { useCursorList } from "@/lib/useCursorList";

function typeLabel(type: string): string {
  const words = type.toLowerCase().split("_").join(" ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export default function WalletPage() {
  const api = useApi();
  const [wallet, setWallet] = useState<Wallet | null>(null);
  const [walletError, setWalletError] = useState<unknown>(null);
  const fetchPage = useCallback((cursor: number | null) => listWalletTransactions(api, { cursor }), [api]);
  const { items, loading, error, hasMore, loadMore, reload } = useCursorList(fetchPage);

  const refreshWallet = useCallback(() => {
    getWallet(api).then(setWallet, setWalletError);
  }, [api]);

  useEffect(() => {
    refreshWallet();
  }, [refreshWallet]);

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-xl font-semibold">Wallet</h1>
          <p className="text-sm text-slate-500">Available balance, settled after delivery. Funds in escrow are not shown here.</p>
        </div>
        <button
          type="button"
          onClick={() => {
            refreshWallet();
            reload();
          }}
          className="rounded border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-100"
        >
          Refresh
        </button>
      </div>

      <section className="rounded border border-slate-200 bg-white p-5">
        <p className="text-sm text-slate-500">Available</p>
        <p className="text-3xl font-semibold tabular-nums">{wallet ? formatMinor(wallet.balance.amountMinor) : "…"}</p>
        {wallet && <p className="mt-1 break-all font-mono text-xs text-slate-400">{wallet.accountCode}</p>}
      </section>
      <ErrorBanner error={walletError} />

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Ledger entries</h2>
        <ErrorBanner error={error} />
        <div className="overflow-x-auto rounded border border-slate-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase text-slate-500">
              <tr>
                <th className="px-4 py-2">Entry</th>
                <th className="px-4 py-2">Type</th>
                <th className="px-4 py-2">Shipment</th>
                <th className="px-4 py-2 text-right">Amount</th>
                <th className="px-4 py-2">Time</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {items.map((entry) => (
                <tr key={entry.entryId}>
                  <td className="px-4 py-2 font-mono text-xs">#{entry.entryId}</td>
                  <td className="px-4 py-2">{typeLabel(entry.type)}</td>
                  <td className="px-4 py-2 font-mono text-xs">
                    {entry.shipmentId ? (
                      <Link href={`/merchant/shipments/${entry.shipmentId}`} className="underline">
                        {entry.shipmentId.slice(0, 8)}…
                      </Link>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td
                    className={`px-4 py-2 text-right tabular-nums ${entry.direction === "CREDIT" ? "text-emerald-700" : "text-red-700"}`}
                  >
                    {entry.direction === "CREDIT" ? "+" : "−"}
                    {formatMinor(entry.amountMinor)}
                  </td>
                  <td className="px-4 py-2 text-slate-600">{new Date(entry.createdAt).toLocaleString()}</td>
                </tr>
              ))}
              {!loading && items.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-slate-500">
                    No ledger entries yet. Earnings appear after a shipment is delivered.
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
      </section>
    </div>
  );
}
