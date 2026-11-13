const TONES: Record<string, string> = {
  PENDING_PAYMENT: "bg-amber-100 text-amber-800",
  PAID: "bg-sky-100 text-sky-800",
  AT_WAREHOUSE: "bg-sky-100 text-sky-800",
  READY_FOR_DISPATCH: "bg-indigo-100 text-indigo-800",
  ASSIGNED: "bg-indigo-100 text-indigo-800",
  OUT_FOR_DELIVERY: "bg-violet-100 text-violet-800",
  DELIVERED: "bg-emerald-100 text-emerald-800",
  DELIVERY_FAILED: "bg-red-100 text-red-800",
  RETURN_TO_WAREHOUSE: "bg-orange-100 text-orange-800",
  DAMAGED: "bg-red-100 text-red-800",
  ON_HOLD: "bg-slate-200 text-slate-700",
  CANCELLED: "bg-slate-200 text-slate-700",
  LOST: "bg-red-100 text-red-800",
};

export function statusLabel(status: string): string {
  const words = status.toLowerCase().split("_").join(" ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`inline-block whitespace-nowrap rounded px-2 py-0.5 text-xs font-medium ${TONES[status] ?? "bg-slate-100 text-slate-700"}`}>
      {statusLabel(status)}
    </span>
  );
}
