"use client";

import { useMemo } from "react";
import { useAuth } from "../auth/AuthProvider";
import { createApiClient, type ApiClient } from "./client";

export function useApi(): ApiClient {
  const { getAccessToken, refresh } = useAuth();
  return useMemo(() => createApiClient({ getAccessToken, refresh }), [getAccessToken, refresh]);
}
