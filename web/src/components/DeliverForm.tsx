"use client";

import { useState, type FormEvent } from "react";
import { newIdempotencyKey } from "@/lib/api/client";
import type { Point } from "@/lib/api/merchant";
import { deliverShipment, uploadPodFile, type PodFile } from "@/lib/api/operations";
import { useApi } from "@/lib/api/useApi";
import { distanceMeters, POD_RADIUS_METERS } from "@/lib/geo";
import { isOtp, sampleProofImage } from "@/lib/sampleProof";
import { ErrorBanner } from "./ErrorBanner";

const INPUT = "w-full rounded border border-slate-300 px-3 py-2";

interface Proof {
  blob: Blob;
  name: string;
  uploaded: PodFile | null;
}

function ProofPicker({
  label,
  required,
  proof,
  onChange,
  sample,
}: {
  label: string;
  required: boolean;
  proof: Proof | null;
  onChange: (proof: Proof | null) => void;
  sample: () => Promise<Blob>;
}) {
  return (
    <div className="space-y-1">
      <span className="text-sm font-medium text-slate-700">
        {label}
        {required ? "" : " (optional)"}
      </span>
      <div className="flex flex-wrap items-center gap-2">
        <input
          type="file"
          accept="image/png,image/jpeg"
          aria-label={`${label} file`}
          onChange={(e) => {
            const file = e.target.files?.[0];
            onChange(file ? { blob: file, name: file.name, uploaded: null } : null);
          }}
          className="text-sm"
        />
        <button
          type="button"
          onClick={() => void sample().then((blob) => onChange({ blob, name: `sample-${label.toLowerCase()}.png`, uploaded: null }))}
          className="rounded border border-slate-300 px-3 py-1 text-sm hover:bg-slate-100"
        >
          Use sample
        </button>
      </div>
      {proof && (
        <p className="text-xs text-slate-500">
          {proof.name} · {Math.round(proof.blob.size / 1024) || 1} KB{proof.uploaded ? " · uploaded" : ""}
        </p>
      )}
    </div>
  );
}

export function DeliverForm({
  shipmentId,
  recipientName,
  destination,
  position,
  onDelivered,
}: {
  shipmentId: string;
  recipientName: string;
  destination: Point;
  position: Point | null;
  onDelivered: () => void;
}) {
  const api = useApi();
  const [photo, setPhoto] = useState<Proof | null>(null);
  const [signature, setSignature] = useState<Proof | null>(null);
  const [name, setName] = useState(recipientName);
  const [otp, setOtp] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const distance = position ? distanceMeters(position, destination) : null;
  const valid = photo !== null && position !== null && name.trim() !== "" && isOtp(otp);

  async function upload(kind: PodFile["kind"], proof: Proof, set: (p: Proof) => void): Promise<PodFile> {
    if (proof.uploaded) {
      return proof.uploaded;
    }
    const uploaded = await uploadPodFile(api, shipmentId, kind, proof.blob, proof.name);
    set({ ...proof, uploaded });
    return uploaded;
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!photo || !position) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const photoFile = await upload("PHOTO", photo, setPhoto);
      const signatureFile = signature ? await upload("SIGNATURE", signature, setSignature) : null;
      await deliverShipment(
        api,
        shipmentId,
        {
          photoFileId: photoFile.fileId,
          ...(signatureFile ? { signatureFileId: signatureFile.fileId } : {}),
          latitude: position.latitude,
          longitude: position.longitude,
          accuracyMeters: 5,
          recipientName: name.trim(),
          otp,
        },
        newIdempotencyKey(),
      );
      onDelivered();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4 rounded border border-slate-200 bg-white p-5">
      <h3 className="text-lg font-semibold">Complete delivery</h3>
      <p className={`text-sm ${distance !== null && distance > POD_RADIUS_METERS ? "text-amber-700" : "text-slate-600"}`}>
        {distance === null
          ? "Send a GPS ping first: the delivery uses your last reported position."
          : `Last position is ${Math.round(distance)} m from the drop-off (limit ${POD_RADIUS_METERS} m).`}
      </p>
      <ProofPicker label="Photo" required proof={photo} onChange={setPhoto} sample={() => sampleProofImage("PHOTO")} />
      <ProofPicker label="Signature" required={false} proof={signature} onChange={setSignature} sample={() => sampleProofImage("SIGNATURE")} />
      <label className="block space-y-1 text-sm font-medium text-slate-700">
        Recipient name
        <input value={name} onChange={(e) => setName(e.target.value)} className={INPUT} />
      </label>
      <label className="block space-y-1 text-sm font-medium text-slate-700">
        Recipient OTP
        <input
          inputMode="numeric"
          autoComplete="off"
          placeholder="6-digit code from the recipient"
          value={otp}
          onChange={(e) => setOtp(e.target.value.trim())}
          className={`${INPUT} font-mono tracking-widest`}
        />
        <span className="block text-xs font-normal text-slate-500">
          Development: the recipient SMS is written to the sink file. Read the latest message with{" "}
          <code>docker exec routemesh-notification-service tail -n 3 /sms/sink.jsonl</code>.
        </span>
      </label>
      <ErrorBanner error={error} />
      <button
        type="submit"
        disabled={busy || !valid}
        className="rounded bg-emerald-700 px-4 py-2 font-medium text-white hover:bg-emerald-600 disabled:opacity-50"
      >
        {busy ? "Submitting…" : "Deliver"}
      </button>
    </form>
  );
}
