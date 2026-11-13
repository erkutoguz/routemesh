"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { RoleShell } from "@/components/RoleShell";

const TABS = [
  { href: "/merchant/quote", label: "New shipment" },
  { href: "/merchant/shipments", label: "Shipments" },
  { href: "/merchant/wallet", label: "Wallet" },
];

export default function MerchantLayout({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  return (
    <RoleShell role="MERCHANT">
      <nav className="mb-6 flex gap-1 border-b border-slate-200">
        {TABS.map((tab) => {
          const active = pathname.startsWith(tab.href);
          return (
            <Link
              key={tab.href}
              href={tab.href}
              className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${
                active ? "border-slate-900 text-slate-900" : "border-transparent text-slate-500 hover:text-slate-900"
              }`}
            >
              {tab.label}
            </Link>
          );
        })}
      </nav>
      {children}
    </RoleShell>
  );
}
