"use client";

import { useRef, useState, type FormEvent } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { initialLocation, LocationField, locationPoint, type LocationValue } from "@/components/LocationField";
import { PaymentPanel } from "@/components/PaymentPanel";
import { newIdempotencyKey } from "@/lib/api/client";
import {
  createQuote,
  createShipment,
  type Quote,
  type ServiceLevel,
  type ShipmentCreated,
} from "@/lib/api/merchant";
import { useApi } from "@/lib/api/useApi";
import { formatMinor, surgeLabel } from "@/lib/money";
import { isValidPhone } from "@/lib/places";

const INPUT = "w-full rounded border border-slate-300 px-3 py-2";
const BUTTON = "rounded bg-slate-900 px-4 py-2 font-medium text-white hover:bg-slate-700 disabled:opacity-50";

export default function QuotePage() {
  const api = useApi();
  const [pickup, setPickup] = useState<LocationValue>(initialLocation("sultanahmet"));
  const [dropoff, setDropoff] = useState<LocationValue>(initialLocation("kadikoy"));
  const [level, setLevel] = useState<ServiceLevel>("STANDARD");
  const [weight, setWeight] = useState("1500");
  const [quote, setQuote] = useState<Quote | null>(null);
  const [quoting, setQuoting] = useState(false);
  const [quoteError, setQuoteError] = useState<unknown>(null);

  const [name, setName] = useState("");
  const [phone, setPhone] = useState("");
  const [address, setAddress] = useState("");
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<unknown>(null);
  const [created, setCreated] = useState<ShipmentCreated | null>(null);
  const shipmentKey = useRef<{ quoteId: string; key: string } | null>(null);

  const origin = locationPoint(pickup);
  const destination = locationPoint(dropoff);
  const grams = Number(weight);
  const weightValid = Number.isInteger(grams) && grams >= 1 && grams <= 30000;

  async function requestQuote(event: FormEvent) {
    event.preventDefault();
    if (!origin || !destination || !weightValid) {
      return;
    }
    setQuoting(true);
    setQuoteError(null);
    setQuote(null);
    try {
      setQuote(
        await createQuote(
          api,
          { origin, destination, serviceLevel: level, weightGrams: grams },
          newIdempotencyKey(),
        ),
      );
    } catch (e) {
      setQuoteError(e);
    } finally {
      setQuoting(false);
    }
  }

  async function submitShipment(event: FormEvent) {
    event.preventDefault();
    if (!quote) {
      return;
    }
    if (shipmentKey.current?.quoteId !== quote.id) {
      shipmentKey.current = { quoteId: quote.id, key: newIdempotencyKey() };
    }
    setCreating(true);
    setCreateError(null);
    try {
      setCreated(
        await createShipment(
          api,
          {
            quoteId: quote.id,
            recipientName: name.trim(),
            recipientPhone: phone.trim(),
            deliveryAddress: address.trim(),
          },
          shipmentKey.current.key,
        ),
      );
    } catch (e) {
      setCreateError(e);
    } finally {
      setCreating(false);
    }
  }

  const recipientValid = name.trim() !== "" && address.trim() !== "" && isValidPhone(phone.trim());

  if (created) {
    return <PaymentPanel created={created} />;
  }

  return (
    <div className="grid gap-8 lg:grid-cols-2">
      <form onSubmit={requestQuote} className="space-y-5">
        <h1 className="text-xl font-semibold">1. Get a quote</h1>
        <LocationField label="Pickup" value={pickup} onChange={setPickup} />
        <LocationField label="Drop-off" value={dropoff} onChange={setDropoff} />
        <div className="grid grid-cols-2 gap-3">
          <label className="space-y-1 text-sm font-medium text-slate-700">
            Service level
            <select value={level} onChange={(e) => setLevel(e.target.value as ServiceLevel)} className={`${INPUT} bg-white`}>
              <option value="STANDARD">Standard (4 h)</option>
              <option value="EXPRESS">Express (2 h)</option>
            </select>
          </label>
          <label className="space-y-1 text-sm font-medium text-slate-700">
            Weight (grams)
            <input inputMode="numeric" value={weight} onChange={(e) => setWeight(e.target.value)} className={INPUT} />
            {!weightValid && <span className="block text-xs text-red-700">1 to 30000 grams.</span>}
          </label>
        </div>
        <button type="submit" disabled={quoting || !origin || !destination || !weightValid} className={BUTTON}>
          {quoting ? "Pricing…" : "Get quote"}
        </button>
        <ErrorBanner error={quoteError} />
      </form>

      <div className="space-y-6">
        {quote && (
          <section className="space-y-3 rounded border border-slate-200 bg-white p-5" aria-label="Quote">
            <div className="flex items-baseline justify-between">
              <span className="text-3xl font-semibold">{formatMinor(quote.finalAmount.amountMinor)}</span>
              <span className="text-sm text-slate-500">valid until {new Date(quote.expiresAt).toLocaleTimeString()}</span>
            </div>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
              <dt className="text-slate-500">Zone</dt>
              <dd className="font-mono">{quote.zoneCode}</dd>
              <dt className="text-slate-500">Base price</dt>
              <dd>{formatMinor(quote.basePrice.amountMinor)}</dd>
              <dt className="text-slate-500">Surge</dt>
              <dd>{surgeLabel(quote.surgeBps)}</dd>
              <dt className="text-slate-500">Distance</dt>
              <dd>{(quote.distanceMeters / 1000).toFixed(1)} km</dd>
              <dt className="text-slate-500">SLA</dt>
              <dd>{quote.slaMinutes / 60} h</dd>
            </dl>
          </section>
        )}

        {quote && (
          <form onSubmit={submitShipment} className="space-y-3">
            <h2 className="text-xl font-semibold">2. Recipient</h2>
            <input aria-label="Recipient name" placeholder="Recipient name" value={name} onChange={(e) => setName(e.target.value)} className={INPUT} />
            <input aria-label="Recipient phone" placeholder="Phone, e.g. +905551112233" value={phone} onChange={(e) => setPhone(e.target.value)} className={INPUT} />
            {phone !== "" && !isValidPhone(phone.trim()) && <p className="text-xs text-red-700">10 to 15 digits, optional leading +.</p>}
            <textarea aria-label="Delivery address" placeholder="Delivery address" value={address} onChange={(e) => setAddress(e.target.value)} rows={3} className={INPUT} />
            <button type="submit" disabled={creating || !recipientValid} className={BUTTON}>
              {creating ? "Creating…" : `Create shipment for ${formatMinor(quote.finalAmount.amountMinor)}`}
            </button>
            <ErrorBanner error={createError} />
          </form>
        )}
      </div>
    </div>
  );
}
