"use client";

import { useEffect, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth/AuthProvider";
import { ROLE_HOME, ROLE_LABEL, type Role } from "@/lib/auth/roles";

export function RoleShell({ role, children }: { role: Role; children: ReactNode }) {
  const { status, roles, username, subject, login, logout } = useAuth();
  const router = useRouter();
  const allowed = roles.includes(role);

  useEffect(() => {
    if (status === "anonymous") {
      void login();
    } else if (status === "authenticated" && !allowed) {
      router.replace("/");
    }
  }, [status, allowed, login, router]);

  if (status !== "authenticated" || !allowed) {
    return <p className="p-8 text-slate-500">Checking your session…</p>;
  }

  return (
    <div className="min-h-screen">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-6 py-3">
          <div className="flex items-center gap-3">
            <span className="text-lg font-semibold tracking-tight">RouteMesh</span>
            <span className="rounded bg-slate-900 px-2 py-0.5 text-xs font-medium text-white">{ROLE_LABEL[role]}</span>
          </div>
          <div className="flex items-center gap-4 text-sm text-slate-600">
            {roles.length > 1 && (
              <nav className="flex gap-3">
                {roles.map((r) => (
                  <a key={r} href={ROLE_HOME[r]} className={r === role ? "font-semibold text-slate-900" : "hover:underline"}>
                    {ROLE_LABEL[r]}
                  </a>
                ))}
              </nav>
            )}
            <span title={subject ?? undefined}>{username}</span>
            <button
              type="button"
              onClick={() => void logout()}
              className="rounded border border-slate-300 px-3 py-1 hover:bg-slate-100"
            >
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-6 py-8">{children}</main>
    </div>
  );
}
