"use client";

import { useEffect, useState } from "react";

interface Page<T, C> {
  items: T[];
  nextCursor: C | null;
}

export function useCursorList<T, C>(fetchPage: (cursor: C | null) => Promise<Page<T, C>>) {
  const [source, setSource] = useState(() => fetchPage);
  const [items, setItems] = useState<T[]>([]);
  const [cursor, setCursor] = useState<C | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [reloads, setReloads] = useState(0);

  if (source !== fetchPage) {
    setSource(() => fetchPage);
    setItems([]);
    setCursor(null);
    setError(null);
    setLoading(true);
  }

  useEffect(() => {
    let cancelled = false;
    fetchPage(null).then(
      (page) => {
        if (!cancelled) {
          setItems(page.items);
          setCursor(page.nextCursor);
          setError(null);
          setLoading(false);
        }
      },
      (e: unknown) => {
        if (!cancelled) {
          setError(e);
          setLoading(false);
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, [fetchPage, reloads]);

  function loadMore() {
    if (cursor === null) {
      return;
    }
    setLoading(true);
    fetchPage(cursor).then(
      (page) => {
        setItems((previous) => [...previous, ...page.items]);
        setCursor(page.nextCursor);
        setLoading(false);
      },
      (e: unknown) => {
        setError(e);
        setLoading(false);
      },
    );
  }

  function reload() {
    setLoading(true);
    setReloads((n) => n + 1);
  }

  return { items, loading, error, hasMore: cursor !== null, loadMore, reload };
}
