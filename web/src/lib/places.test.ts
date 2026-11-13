import { describe, expect, it } from "vitest";
import { isValidPhone, parseCoordinate } from "./places";

describe("parseCoordinate", () => {
  it("accepts dot and comma decimals within range", () => {
    expect(parseCoordinate("41.0082", -90, 90)).toBe(41.0082);
    expect(parseCoordinate(" 28,9784 ", -180, 180)).toBe(28.9784);
  });

  it("rejects empty, non-numeric and out-of-range input", () => {
    expect(parseCoordinate("", -90, 90)).toBeNull();
    expect(parseCoordinate("abc", -90, 90)).toBeNull();
    expect(parseCoordinate("91", -90, 90)).toBeNull();
    expect(parseCoordinate("Infinity", -180, 180)).toBeNull();
  });
});

describe("isValidPhone", () => {
  it("matches the gateway rule", () => {
    expect(isValidPhone("+905551112233")).toBe(true);
    expect(isValidPhone("05551112233")).toBe(true);
    expect(isValidPhone("12345")).toBe(false);
    expect(isValidPhone("+90 555 111 22 33")).toBe(false);
  });
});
