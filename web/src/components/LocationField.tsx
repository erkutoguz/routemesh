"use client";

import { CUSTOM_PLACE, PLACES, parseCoordinate } from "@/lib/places";
import type { Point } from "@/lib/api/merchant";

export interface LocationValue {
  placeId: string;
  latitude: string;
  longitude: string;
}

export function initialLocation(placeId: string): LocationValue {
  const place = PLACES.find((p) => p.id === placeId);
  return { placeId, latitude: String(place?.latitude ?? ""), longitude: String(place?.longitude ?? "") };
}

export function locationPoint(value: LocationValue): Point | null {
  const latitude = parseCoordinate(value.latitude, -90, 90);
  const longitude = parseCoordinate(value.longitude, -180, 180);
  return latitude === null || longitude === null ? null : { latitude, longitude };
}

export function LocationField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: LocationValue;
  onChange: (value: LocationValue) => void;
}) {
  const custom = value.placeId === CUSTOM_PLACE;
  const invalid = locationPoint(value) === null;

  function select(placeId: string) {
    if (placeId === CUSTOM_PLACE) {
      onChange({ ...value, placeId });
      return;
    }
    onChange(initialLocation(placeId));
  }

  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium text-slate-700">{label}</legend>
      <select
        aria-label={`${label} place`}
        value={value.placeId}
        onChange={(e) => select(e.target.value)}
        className="w-full rounded border border-slate-300 bg-white px-3 py-2"
      >
        {PLACES.map((place) => (
          <option key={place.id} value={place.id}>
            {place.label}
          </option>
        ))}
        <option value={CUSTOM_PLACE}>Custom coordinates…</option>
      </select>
      <div className="grid grid-cols-2 gap-2">
        <input
          aria-label={`${label} latitude`}
          inputMode="decimal"
          value={value.latitude}
          readOnly={!custom}
          onChange={(e) => onChange({ ...value, latitude: e.target.value })}
          className="rounded border border-slate-300 px-3 py-2 read-only:bg-slate-100 read-only:text-slate-500"
        />
        <input
          aria-label={`${label} longitude`}
          inputMode="decimal"
          value={value.longitude}
          readOnly={!custom}
          onChange={(e) => onChange({ ...value, longitude: e.target.value })}
          className="rounded border border-slate-300 px-3 py-2 read-only:bg-slate-100 read-only:text-slate-500"
        />
      </div>
      {custom && invalid && <p className="text-xs text-red-700">Enter latitude (−90…90) and longitude (−180…180).</p>}
    </fieldset>
  );
}
