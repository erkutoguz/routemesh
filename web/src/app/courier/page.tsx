"use client";

import dynamic from "next/dynamic";
import { useCallback, useEffect, useRef, useState } from "react";
import { DeliverForm } from "@/components/DeliverForm";
import { ErrorBanner } from "@/components/ErrorBanner";
import { RoleShell } from "@/components/RoleShell";
import { StatusBadge } from "@/components/StatusBadge";
import { newIdempotencyKey } from "@/lib/api/client";
import { getShipment, type Point, type Shipment } from "@/lib/api/merchant";
import {
  acceptOffer,
  departShipment,
  getCourier,
  listOffers,
  newPingSession,
  rejectOffer,
  sendPing,
  setCourierStatus,
} from "@/lib/api/operations";
import { useApi } from "@/lib/api/useApi";
import { DEPOT, distanceMeters, offsetPoint } from "@/lib/geo";
import { usePoll } from "@/lib/usePoll";

const TrackingMap = dynamic(() => import("@/components/TrackingMap"), {
  ssr: false,
  loading: () => <div className="h-[420px] animate-pulse rounded bg-slate-200" />,
});

const KEEPALIVE_MS = 30_000;
const BUTTON = "rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-100 disabled:opacity-50";

export default function CourierPage() {
  return (
    <RoleShell role="COURIER">
      <CourierConsole />
    </RoleShell>
  );
}

function CourierConsole() {
  const api = useApi();
  const [session] = useState(newPingSession);
  const [lastPing, setLastPing] = useState<Point | null>(null);
  const [pingNote, setPingNote] = useState<string | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const fetchMe = useCallback(() => getCourier(api), [api]);
  const fetchOffers = useCallback(() => listOffers(api), [api]);
  const me = usePoll(fetchMe, 4000);
  const offers = usePoll(fetchOffers, 4000);

  const activeId = me.data?.activeShipmentId ?? null;
  const fetchShipment = useCallback(() => getShipment(api, activeId as string), [api, activeId]);
  const shipment = usePoll(fetchShipment, 3000, activeId !== null);
  const active: Shipment | null = activeId !== null && shipment.data?.id === activeId ? shipment.data : null;

  const online = me.data !== null && me.data.status !== "OFFLINE";
  const lastPingRef = useRef(lastPing);
  useEffect(() => {
    lastPingRef.current = lastPing;
  }, [lastPing]);

  const ping = useCallback(
    async (point: Point, label: string) => {
      setActionError(null);
      try {
        const result = await sendPing(api, session, point);
        setLastPing(point);
        setPingNote(`${label}: ${result.accepted ? "accepted" : "dropped as stale"} (#${session.sequence})`);
      } catch (e) {
        setActionError(e);
      }
    },
    [api, session],
  );

  useEffect(() => {
    if (!online) {
      return;
    }
    const timer = setInterval(() => {
      const point = lastPingRef.current;
      if (point) {
        void sendPing(api, session, point).catch(() => undefined);
      }
    }, KEEPALIVE_MS);
    return () => clearInterval(timer);
  }, [online, api, session]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      me.refresh();
      offers.refresh();
      shipment.refresh();
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(false);
    }
  }

  const openOffers = (offers.data ?? []).filter((o) => o.status === "OFFERED");
  const destination = active?.deliveryLocation ?? null;
  const toDropoff = destination && lastPing ? distanceMeters(lastPing, destination) : null;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Courier console</h1>
        <p className="text-sm text-slate-500">Go online, accept an offer, report your position and deliver with proof.</p>
      </div>

      <ErrorBanner error={me.error ?? offers.error} />
      <ErrorBanner error={actionError} />

      <section className="flex flex-wrap items-center justify-between gap-3 rounded border border-slate-200 bg-white p-4">
        <div className="flex items-center gap-3">
          <span className="text-sm text-slate-500">Status</span>
          <span className="rounded bg-slate-900 px-2 py-0.5 text-xs font-medium text-white">{me.data?.status ?? "…"}</span>
          {pingNote && <span className="text-xs text-slate-500">{pingNote}</span>}
        </div>
        <div className="flex flex-wrap gap-2">
          {me.data?.status === "OFFLINE" && (
            <button type="button" disabled={busy} className={BUTTON} onClick={() => void run(() => setCourierStatus(api, "IDLE"))}>
              Go online
            </button>
          )}
          {me.data?.status === "IDLE" && (
            <button type="button" disabled={busy} className={BUTTON} onClick={() => void run(() => setCourierStatus(api, "OFFLINE"))}>
              Go offline
            </button>
          )}
          <button type="button" className={BUTTON} onClick={() => void ping(DEPOT, "At depot")}>
            Ping from depot
          </button>
        </div>
      </section>

      {active === null && (
        <section className="space-y-3">
          <h2 className="text-lg font-semibold">Offers</h2>
          {openOffers.length === 0 && (
            <p className="rounded border border-dashed border-slate-300 p-6 text-center text-sm text-slate-500">
              No open offers. Go online and ping from the depot so dispatch can find you.
            </p>
          )}
          {openOffers.map((offer) => (
            <div key={offer.offerId} className="flex flex-wrap items-center justify-between gap-3 rounded border border-slate-200 bg-white p-4">
              <div className="text-sm">
                <p className="font-mono text-xs text-slate-500">{offer.shipmentId}</p>
                <p>
                  {(offer.weightGrams / 1000).toFixed(1)} kg · expires {new Date(offer.expiresAt).toLocaleTimeString()}
                </p>
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => void run(() => acceptOffer(api, offer.offerId))}
                  className="rounded bg-emerald-700 px-4 py-1.5 text-sm font-medium text-white hover:bg-emerald-600 disabled:opacity-50"
                >
                  Accept
                </button>
                <button type="button" disabled={busy} className={BUTTON} onClick={() => void run(() => rejectOffer(api, offer.offerId))}>
                  Reject
                </button>
              </div>
            </div>
          ))}
        </section>
      )}

      {active && destination && (
        <section className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="text-lg font-semibold">Active delivery</h2>
              <p className="break-all font-mono text-xs text-slate-500">{active.id}</p>
            </div>
            <StatusBadge status={active.status} />
          </div>

          <dl className="grid gap-x-8 gap-y-1 rounded border border-slate-200 bg-white p-4 text-sm sm:grid-cols-2">
            <div className="flex justify-between"><dt className="text-slate-500">Recipient</dt><dd>{active.recipientName}</dd></div>
            <div className="flex justify-between"><dt className="text-slate-500">Phone</dt><dd>{active.recipientPhone}</dd></div>
            <div className="flex justify-between sm:col-span-2"><dt className="text-slate-500">Address</dt><dd className="text-right">{active.deliveryAddress}</dd></div>
          </dl>

          <div className="space-y-2">
            <p className="text-sm font-medium text-slate-700">
              GPS telemetry
              {toDropoff !== null && <span className="ml-2 font-normal text-slate-500">{Math.round(toDropoff)} m from the drop-off</span>}
            </p>
            <div className="flex flex-wrap gap-2">
              <button type="button" className={BUTTON} onClick={() => void ping(DEPOT, "Depot")}>
                Go to pickup (depot)
              </button>
              <button type="button" className={BUTTON} onClick={() => void ping(offsetPoint(destination, 120, 200), "120 m out")}>
                120 m from drop-off (outside 50 m)
              </button>
              <button type="button" className={BUTTON} onClick={() => void ping(offsetPoint(destination, 15, 200), "15 m out")}>
                15 m from drop-off
              </button>
              <button type="button" className={BUTTON} onClick={() => void ping(destination, "Drop-off")}>
                At drop-off
              </button>
            </div>
            <p className="text-xs text-slate-500">Or click anywhere on the map to report that position.</p>
            <TrackingMap
              pickup={DEPOT}
              pickupLabel="Depot"
              delivery={destination}
              courier={lastPing}
              onPick={(point) => void ping(point, "Map click")}
            />
          </div>

          {active.status === "ASSIGNED" && (
            <button
              type="button"
              disabled={busy}
              onClick={() => void run(() => departShipment(api, active.id, newIdempotencyKey()))}
              className="rounded bg-slate-900 px-4 py-2 font-medium text-white hover:bg-slate-700 disabled:opacity-50"
            >
              Depart with the parcel
            </button>
          )}

          {active.status === "OUT_FOR_DELIVERY" && (
            <DeliverForm
              shipmentId={active.id}
              recipientName={active.recipientName}
              destination={destination}
              position={lastPing}
              onDelivered={() => {
                me.refresh();
                shipment.refresh();
              }}
            />
          )}
        </section>
      )}

      {activeId !== null && active === null && <p className="text-sm text-slate-500">Loading the active delivery…</p>}
    </div>
  );
}
