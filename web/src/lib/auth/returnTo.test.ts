import { describe, expect, it } from "vitest";
import { currentReturnTo, safeReturnTo } from "./returnTo";

describe("safeReturnTo", () => {
  it("keeps same-origin paths with query", () => {
    expect(safeReturnTo("/merchant/quote")).toBe("/merchant/quote");
    expect(safeReturnTo("/merchant/shipments?x=1")).toBe("/merchant/shipments?x=1");
  });

  it("rejects open-redirect shapes", () => {
    expect(safeReturnTo("//evil.example")).toBe("/");
    expect(safeReturnTo("https://evil.example")).toBe("/");
    expect(safeReturnTo("/\\evil.example")).toBe("/");
    expect(safeReturnTo(undefined)).toBe("/");
    expect(safeReturnTo(42)).toBe("/");
  });

  it("never returns to the callback page itself", () => {
    expect(safeReturnTo("/callback")).toBe("/");
    expect(safeReturnTo("/callback?code=1")).toBe("/");
  });
});

describe("currentReturnTo", () => {
  it("joins path and query", () => {
    expect(currentReturnTo({ pathname: "/courier", search: "?a=1" })).toBe("/courier?a=1");
    expect(currentReturnTo({ pathname: "/callback", search: "" })).toBe("/");
  });
});
