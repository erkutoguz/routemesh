import { describe, expect, it } from "vitest";
import { formatMinor, surgeLabel } from "./money";

describe("formatMinor", () => {
  it("formats kuruş as lira with two decimals", () => {
    expect(formatMinor(13950)).toBe("₺139,50");
    expect(formatMinor(5)).toBe("₺0,05");
    expect(formatMinor(100)).toBe("₺1,00");
    expect(formatMinor(0)).toBe("₺0,00");
  });

  it("groups thousands the Turkish way", () => {
    expect(formatMinor(123456789)).toBe("₺1.234.567,89");
  });

  it("keeps the sign in front of the symbol", () => {
    expect(formatMinor(-1001)).toBe("-₺10,01");
  });

  it("never rounds: 1001 stays exact", () => {
    expect(formatMinor(1001)).toBe("₺10,01");
  });
});

describe("surgeLabel", () => {
  it("renders basis points as a multiplier", () => {
    expect(surgeLabel(10000)).toBe("1×");
    expect(surgeLabel(12500)).toBe("1,25×");
    expect(surgeLabel(15000)).toBe("1,5×");
  });
});
