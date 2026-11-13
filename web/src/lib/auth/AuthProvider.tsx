"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { User } from "oidc-client-ts";
import { userManager } from "./oidc";
import { decodeClaims, rolesFromToken, type Role } from "./roles";

type Status = "loading" | "anonymous" | "authenticated";

interface AuthContextValue {
  status: Status;
  username: string | null;
  subject: string | null;
  roles: Role[];
  login: () => Promise<void>;
  logout: () => Promise<void>;
  getAccessToken: () => Promise<string | null>;
  refresh: () => Promise<string | null>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function usable(user: User | null): user is User {
  return user !== null && !user.expired;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<Status>("loading");

  useEffect(() => {
    const manager = userManager();
    const apply = (next: User | null) => {
      setUser(usable(next) ? next : null);
      setStatus(usable(next) ? "authenticated" : "anonymous");
    };
    const onLoaded = (loaded: User) => apply(loaded);
    const onGone = () => apply(null);
    manager.events.addUserLoaded(onLoaded);
    manager.events.addUserUnloaded(onGone);
    manager.events.addSilentRenewError(onGone);
    manager.events.addAccessTokenExpired(onGone);
    manager.getUser().then(apply, () => apply(null));
    return () => {
      manager.events.removeUserLoaded(onLoaded);
      manager.events.removeUserUnloaded(onGone);
      manager.events.removeSilentRenewError(onGone);
      manager.events.removeAccessTokenExpired(onGone);
    };
  }, []);

  const login = useCallback(() => userManager().signinRedirect(), []);
  const logout = useCallback(() => userManager().signoutRedirect(), []);
  const getAccessToken = useCallback(async () => {
    const current = await userManager().getUser();
    return usable(current) ? current.access_token : null;
  }, []);
  const refresh = useCallback(async () => {
    try {
      const renewed = await userManager().signinSilent();
      return renewed?.access_token ?? null;
    } catch {
      return null;
    }
  }, []);

  const value = useMemo<AuthContextValue>(() => {
    const claims = user ? decodeClaims(user.access_token) : null;
    return {
      status,
      username: claims?.preferred_username ?? null,
      subject: claims?.sub ?? null,
      roles: user ? rolesFromToken(user.access_token) : [],
      login,
      logout,
      getAccessToken,
      refresh,
    };
  }, [user, status, login, logout, getAccessToken, refresh]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (value === null) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return value;
}
