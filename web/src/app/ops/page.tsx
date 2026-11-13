import { RoleShell } from "@/components/RoleShell";

export default function OpsPage() {
  return (
    <RoleShell role="OPS">
      <h1 className="text-2xl font-semibold">Operations console</h1>
      <p className="mt-2 text-slate-600">Reconciliation and ledger integrity arrive in a later change.</p>
    </RoleShell>
  );
}
