import { describe, expect, it } from "vitest";
import { decodeClaims, rolesFromToken } from "./roles";

function jwt(claims: object): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  return `${encode({ alg: "RS256" })}.${encode(claims)}.signature`;
}

describe("rolesFromToken", () => {
  it("keeps only console roles, in console order", () => {
    const token = jwt({ realm_access: { roles: ["OPS", "offline_access", "MERCHANT", "WORKFLOW"] } });
    expect(rolesFromToken(token)).toEqual(["MERCHANT", "OPS"]);
  });

  it("returns no roles when the claim is missing", () => {
    expect(rolesFromToken(jwt({ sub: "x" }))).toEqual([]);
  });

  it("returns no roles for a malformed token", () => {
    expect(rolesFromToken("garbage")).toEqual([]);
    expect(rolesFromToken("a.%%%.c")).toEqual([]);
  });
});

describe("decodeClaims", () => {
  it("decodes non-ascii claims", () => {
    expect(decodeClaims(jwt({ preferred_username: "çağrı" })).preferred_username).toBe("çağrı");
  });
});
