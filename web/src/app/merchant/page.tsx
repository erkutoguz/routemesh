import { RoleShell } from "@/components/RoleShell";

export default function MerchantPage() {
  return (
    <RoleShell role="MERCHANT">
      <h1 className="text-2xl font-semibold">Merchant console</h1>
      <p className="mt-2 text-slate-600">Quotes, shipments, live tracking and wallet arrive in the next change.</p>
    </RoleShell>
  );
}
