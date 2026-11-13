"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useApi } from "@/lib/api/useApi";
import { getShipment, pspPaymentId, type ShipmentCreated } from "@/lib/api/merchant";
import { StatusBadge } from "./StatusBadge";
import { ErrorBanner } from "./ErrorBanner";

const POLL_MS = 2000;
const POLL_LIMIT_MS = 90_000;

export function PaymentPanel({ created }: { created: ShipmentCreated }) {
  const api = useApi();
  const [status, setStatus] = useState(created.status ?? "PENDING_PAYMENT");
  const [watching, setWatching] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const pspId = created.checkoutUrl ? pspPaymentId(created.checkoutUrl) : null;

  useEffect(() => {
    if (!watching) {
      return;
    }
    const deadline = Date.now() + POLL_LIMIT_MS;
    let stopped = false;
    const timer = setInterval(async () => {
      try {
        const shipment = await getShipment(api, created.shipmentId);
        if (stopped) {
          return;
        }
        setStatus(shipment.status);
        if (shipment.status !== "PENDING_PAYMENT" || Date.now() > deadline) {
          setWatching(false);
        }
      } catch (e) {
        if (!stopped) {
          setError(e);
        }
      }
    }, POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [watching, api, created.shipmentId]);

  async function complete(outcome: "AUTHORIZE" | "DECLINE") {
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/psp-sim/v1/sim/payments/${pspId}/complete`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(outcome === "DECLINE" ? { outcome: "DECLINE" } : {}),
      });
      if (!response.ok) {
        throw new Error(`PSP simulator answered ${response.status}. Is it running on port 8085?`);
      }
      setWatching(true);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="space-y-4 rounded border border-slate-200 bg-white p-5">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Shipment created</h2>
        <StatusBadge status={status} />
      </div>
      <p className="break-all text-sm text-slate-600">
        Shipment <span className="font-mono">{created.shipmentId}</span>
        {pspId && (
          <>
            <br />
            PSP payment <span className="font-mono">{pspId}</span>
          </>
        )}
      </p>

      {pspId && status === "PENDING_PAYMENT" && (
        <div className="space-y-2">
          <p className="text-sm text-slate-600">
            Development PSP simulator: complete the payment with one click, the webhook then moves the shipment to
            PAID.
          </p>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={busy || watching}
              onClick={() => void complete("AUTHORIZE")}
              className="rounded bg-emerald-700 px-4 py-2 font-medium text-white hover:bg-emerald-600 disabled:opacity-50"
            >
              {watching ? "Waiting for PAID…" : "Pay now (simulator)"}
            </button>
            <button
              type="button"
              disabled={busy || watching}
              onClick={() => void complete("DECLINE")}
              className="rounded border border-slate-300 px-4 py-2 hover:bg-slate-100 disabled:opacity-50"
            >
              Decline
            </button>
          </div>
        </div>
      )}
      {!pspId && status === "PENDING_PAYMENT" && (
        <p className="text-sm text-slate-600">The payment is still being prepared. Check the shipment in a moment.</p>
      )}

      <ErrorBanner error={error} />

      <Link href={`/merchant/shipments/${created.shipmentId}`} className="inline-block text-sm font-medium underline">
        Open shipment
      </Link>
    </section>
  );
}
