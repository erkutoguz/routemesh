"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth/AuthProvider";
import { ROLE_HOME, ROLE_LABEL } from "@/lib/auth/roles";

export default function Home() {
  const { status, roles, username, login, logout } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "authenticated" && roles.length === 1) {
      router.replace(ROLE_HOME[roles[0]]);
    }
  }, [status, roles, router]);

  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center gap-6 px-6">
      <div>
        <h1 className="text-3xl font-semibold tracking-tight">RouteMesh Console</h1>
        <p className="mt-2 text-slate-600">Merchant, warehouse, courier and operations tools for the delivery platform.</p>
      </div>

      {status === "loading" && <p className="text-slate-500">Checking your session…</p>}

      {status === "anonymous" && (
        <button
          type="button"
          onClick={() => void login()}
          className="rounded bg-slate-900 px-4 py-2 font-medium text-white hover:bg-slate-700"
        >
          Sign in
        </button>
      )}

      {status === "authenticated" && roles.length === 0 && (
        <div className="space-y-3">
          <p className="text-slate-700">
            {username} has no console role. Ask an administrator to grant MERCHANT, WAREHOUSE, COURIER or OPS.
          </p>
          <button type="button" onClick={() => void logout()} className="rounded border border-slate-300 px-3 py-1">
            Sign out
          </button>
        </div>
      )}

      {status === "authenticated" && roles.length > 1 && (
        <div className="space-y-3">
          <p className="text-slate-700">Signed in as {username}. Choose a console:</p>
          <ul className="space-y-2">
            {roles.map((role) => (
              <li key={role}>
                <a
                  href={ROLE_HOME[role]}
                  className="block rounded border border-slate-300 bg-white px-4 py-2 font-medium hover:bg-slate-100"
                >
                  {ROLE_LABEL[role]}
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
    </main>
  );
}
