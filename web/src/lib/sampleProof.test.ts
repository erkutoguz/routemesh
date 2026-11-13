import { describe, expect, it } from "vitest";
import { isOtp } from "./sampleProof";

describe("isOtp", () => {
  it("accepts 4 to 8 digits only", () => {
    expect(isOtp("123456")).toBe(true);
    expect(isOtp("1234")).toBe(true);
    expect(isOtp("123")).toBe(false);
    expect(isOtp("12345678 ")).toBe(false);
    expect(isOtp("12a456")).toBe(false);
    expect(isOtp("")).toBe(false);
  });
});
