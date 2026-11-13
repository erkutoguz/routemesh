"use client";

import { useState } from "react";
import { IntegrityPanel } from "@/components/IntegrityPanel";
import { ReconciliationPanel } from "@/components/ReconciliationPanel";
import { RoleShell } from "@/components/RoleShell";

const TABS = [
  { id: "reconciliation", label: "Reconciliation" },
  { id: "integrity", label: "Ledger integrity" },
] as const;

export default function OpsPage() {
  return (
    <RoleShell role="OPS">
      <OpsConsole />
    </RoleShell>
  );
}

function OpsConsole() {
  const [tab, setTab] = useState<(typeof TABS)[number]["id"]>("reconciliation");
  return (
    <div className="space-y-5">
      <h1 className="text-2xl font-semibold">Operations console</h1>
      <nav className="flex gap-1 border-b border-slate-200">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            onClick={() => setTab(t.id)}
            className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
              tab === t.id ? "border-slate-900 text-slate-900" : "border-transparent text-slate-500 hover:text-slate-900"
            }`}
          >
            {t.label}
          </button>
        ))}
      </nav>
      {tab === "reconciliation" ? <ReconciliationPanel /> : <IntegrityPanel />}
    </div>
  );
}
