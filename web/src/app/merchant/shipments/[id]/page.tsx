"use client";

import dynamic from "next/dynamic";
import Link from "next/link";
import { use, useEffect, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api/client";
import {
  getQuote,
  getShipment,
  type Point,
  type Shipment,
} from "@/lib/api/merchant";
import { useApi } from "@/lib/api/useApi";
import { formatMinor } from "@/lib/money";
import { runSse } from "@/lib/sse";
import {
  approachText,
  isTerminalEnd,
  LIVE_STATUSES,
  parseTrackingEvent,
  type LocationEvent,
} from "@/lib/tracking";

const TrackingMap = dynamic(() => import("@/components/TrackingMap"), {
  ssr: false,
  loading: () => (
    <div className="h-[420px] animate-pulse rounded bg-slate-200" />
  ),
});

const STATUS_POLL_MS = 5000;

type Link_ = "idle" | "connecting" | "open" | "reconnecting" | "closed";

export default function ShipmentDetail({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const api = useApi();
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [pickup, setPickup] = useState<Point | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [courier, setCourier] = useState<LocationEvent | null>(null);
  const [notices, setNotices] = useState<string[]>([]);
  const [link, setLink] = useState<Link_>("idle");
  const [unavailable, setUnavailable] = useState<string | null>(null);

  useEffect(() => {
    let stopped = false;
    const load = () =>
      getShipment(api, id).then(
        (s) => {
          if (!stopped) {
            setShipment(s);
          }
        },
        (e) => {
          if (!stopped) {
            setError(e);
          }
        },
      );
    void load();
    const timer = setInterval(load, STATUS_POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [api, id]);

  const quoteId = shipment?.quoteId;
  useEffect(() => {
    if (!quoteId) {
      return;
    }
    getQuote(api, quoteId).then(
      (q) => setPickup(q.origin),
      () => setPickup(null),
    );
  }, [api, quoteId]);

  const live = shipment !== null && LIVE_STATUSES.includes(shipment.status);
  useEffect(() => {
    if (!live) {
      return;
    }
    const controller = new AbortController();
    void runSse({
      signal: controller.signal,
      isTerminal: isTerminalEnd,
      onStatus: (s) => setLink(s),
      open: async (signal) => {
        try {
          return await api.stream(`/api/v1/tracking/${id}/stream`, signal);
        } catch (e) {
          if (
            e instanceof ApiError &&
            e.status >= 400 &&
            e.status < 500 &&
            e.status !== 401 &&
            e.status !== 429
          ) {
            setUnavailable(e.message);
            controller.abort();
          }
          throw e;
        }
      },
      onMessage: (message) => {
        const event = parseTrackingEvent(message);
        if (event?.type === "location") {
          setCourier(event.location);
        } else if (event?.type === "approaching") {
          setNotices((previous) =>
            [approachText(event.kind, event.distanceMeters), ...previous].slice(
              0,
              4,
            ),
          );
        }
      },
    });
    return () => controller.abort();
  }, [live, api, id]);

  if (error && !shipment) {
    return <ErrorBanner error={error} />;
  }
  if (!shipment) {
    return <p className="text-slate-500">Loading shipment…</p>;
  }

  const courierPoint = courier
    ? { latitude: courier.latitude, longitude: courier.longitude }
    : null;

  return (
    <div className="space-y-5">
      <Link
        href="/merchant/shipments"
        className="text-sm text-slate-500 hover:underline"
      >
        ← All shipments
      </Link>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">Shipment</h1>
          <p className="break-all font-mono text-xs text-slate-500">
            {shipment.id}
          </p>
        </div>
        <StatusBadge status={shipment.status} />
      </div>

      <dl className="grid gap-x-8 gap-y-1 rounded border border-slate-200 bg-white p-4 text-sm sm:grid-cols-2">
        <div className="flex justify-between">
          <dt className="text-slate-500">Recipient</dt>
          <dd>{shipment.recipientName}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-slate-500">Phone</dt>
          <dd>{shipment.recipientPhone}</dd>
        </div>
        <div className="flex justify-between sm:col-span-2">
          <dt className="text-slate-500">Address</dt>
          <dd className="text-right">{shipment.deliveryAddress}</dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-slate-500">Amount</dt>
          <dd className="tabular-nums">
            {formatMinor(shipment.finalAmount.amountMinor)}
          </dd>
        </div>
        <div className="flex justify-between">
          <dt className="text-slate-500">Created</dt>
          <dd>{new Date(shipment.createdAt).toLocaleString()}</dd>
        </div>
      </dl>

      {notices.length > 0 && (
        <ul aria-label="Approach notifications" className="space-y-1">
          {notices.map((text, i) => (
            <li
              key={`${i}-${text}`}
              className="rounded border border-violet-200 bg-violet-50 px-3 py-2 text-sm text-violet-900"
            >
              {text}
            </li>
          ))}
        </ul>
      )}

      <div className="flex items-center justify-between text-sm text-slate-600">
        <span>
          {live
            ? link === "open"
              ? courier
                ? `Live — courier ${courier.distanceMeters === null ? "" : Math.round(courier.distanceMeters) + " m from " + (courier.target === "PICKUP" ? "pickup" : "drop-off")}`
                : "Live — waiting for the courier's next location"
              : link === "reconnecting"
                ? "Reconnecting to live tracking…"
                : "Connecting to live tracking…"
            : "Live tracking starts when a courier is assigned."}
        </span>
        {unavailable && <span className="text-red-700">{unavailable}</span>}
      </div>

      <TrackingMap
        pickup={pickup}
        delivery={shipment.deliveryLocation}
        courier={courierPoint}
      />
    </div>
  );
}
