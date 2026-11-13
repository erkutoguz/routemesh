"use client";

import "leaflet/dist/leaflet.css";
import L from "leaflet";
import { useEffect } from "react";
import { Circle, CircleMarker, MapContainer, TileLayer, Tooltip, useMap } from "react-leaflet";
import type { Point } from "@/lib/api/merchant";

export const POD_RADIUS_METERS = 50;

function Fit({ points }: { points: Point[] }) {
  const map = useMap();
  const key = points.map((p) => `${p.latitude},${p.longitude}`).join("|");
  useEffect(() => {
    if (points.length === 0) {
      return;
    }
    const bounds = L.latLngBounds(points.map((p) => [p.latitude, p.longitude] as [number, number]));
    map.fitBounds(bounds, { padding: [48, 48], maxZoom: 16 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, key]);
  return null;
}

export default function TrackingMap({
  pickup,
  delivery,
  courier,
}: {
  pickup: Point | null;
  delivery: Point;
  courier: Point | null;
}) {
  const fitPoints = [delivery, ...(pickup ? [pickup] : [])];
  return (
    <MapContainer
      center={[delivery.latitude, delivery.longitude]}
      zoom={13}
      scrollWheelZoom
      className="h-[420px] w-full rounded border border-slate-200"
    >
      <TileLayer
        url="https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png"
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>'
        subdomains="abcd"
        maxZoom={20}
      />
      {pickup && (
        <CircleMarker center={[pickup.latitude, pickup.longitude]} radius={9} pathOptions={{ color: "#0369a1", fillColor: "#0ea5e9", fillOpacity: 0.9 }}>
          <Tooltip permanent direction="top">Pickup</Tooltip>
        </CircleMarker>
      )}
      <CircleMarker center={[delivery.latitude, delivery.longitude]} radius={9} pathOptions={{ color: "#047857", fillColor: "#10b981", fillOpacity: 0.9 }}>
        <Tooltip permanent direction="top">Drop-off</Tooltip>
      </CircleMarker>
      <Circle
        center={[delivery.latitude, delivery.longitude]}
        radius={POD_RADIUS_METERS}
        pathOptions={{ color: "#047857", weight: 1, dashArray: "4 4", fillOpacity: 0.12 }}
      />
      {courier && (
        <CircleMarker center={[courier.latitude, courier.longitude]} radius={8} pathOptions={{ color: "#6d28d9", fillColor: "#8b5cf6", fillOpacity: 1 }}>
          <Tooltip permanent direction="bottom">Courier</Tooltip>
        </CircleMarker>
      )}
      <Fit points={courier ? [...fitPoints, courier] : fitPoints} />
    </MapContainer>
  );
}
