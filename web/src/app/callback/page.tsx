"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { userManager } from "@/lib/auth/oidc";

let handled = false;

export default function Callback() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (handled) {
      return;
    }
    handled = true;
    userManager()
      .signinRedirectCallback()
      .then(() => router.replace("/"))
      .catch((e: unknown) => setError(e instanceof Error ? e.message : "Sign-in failed"));
  }, [router]);

  if (error) {
    return (
      <main className="mx-auto max-w-md space-y-3 px-6 py-24">
        <p className="text-red-700">Sign-in failed: {error}</p>
        <Link href="/" className="underline">
          Back to start
        </Link>
      </main>
    );
  }
  return <p className="p-8 text-slate-500">Completing sign-in…</p>;
}
