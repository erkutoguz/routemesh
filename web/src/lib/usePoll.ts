"use client";

import { useCallback, useEffect, useState } from "react";

export function usePoll<T>(fetcher: () => Promise<T>, intervalMs: number, enabled = true) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (!enabled) {
      return;
    }
    let cancelled = false;
    const run = () =>
      fetcher().then(
        (value) => {
          if (!cancelled) {
            setData(value);
            setError(null);
          }
        },
        (e: unknown) => {
          if (!cancelled) {
            setError(e);
          }
        },
      );
    void run();
    const timer = setInterval(run, intervalMs);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [fetcher, intervalMs, enabled, tick]);

  const refresh = useCallback(() => setTick((n) => n + 1), []);
  return { data, error, refresh };
}
