export const ROLES = ["MERCHANT", "WAREHOUSE", "COURIER", "OPS"] as const;

export type Role = (typeof ROLES)[number];

export const ROLE_HOME: Record<Role, string> = {
  MERCHANT: "/merchant",
  WAREHOUSE: "/warehouse",
  COURIER: "/courier",
  OPS: "/ops",
};

export const ROLE_LABEL: Record<Role, string> = {
  MERCHANT: "Merchant",
  WAREHOUSE: "Warehouse",
  COURIER: "Courier",
  OPS: "Operations",
};

interface TokenClaims {
  sub?: string;
  preferred_username?: string;
  realm_access?: { roles?: string[] };
}

export function decodeClaims(accessToken: string): TokenClaims {
  const payload = accessToken.split(".")[1];
  if (!payload) {
    return {};
  }
  try {
    const base64 = payload.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as TokenClaims;
  } catch {
    return {};
  }
}

export function rolesFromToken(accessToken: string): Role[] {
  const granted = decodeClaims(accessToken).realm_access?.roles ?? [];
  return ROLES.filter((role) => granted.includes(role));
}
