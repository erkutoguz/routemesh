export function formatMinor(amountMinor: number): string {
  const negative = amountMinor < 0;
  const abs = Math.abs(amountMinor);
  const major = Math.floor(abs / 100);
  const minor = abs % 100;
  const grouped = new Intl.NumberFormat("tr-TR", { maximumFractionDigits: 0 }).format(major);
  return `${negative ? "-" : ""}₺${grouped},${String(minor).padStart(2, "0")}`;
}

export function surgeLabel(surgeBps: number): string {
  const whole = Math.floor(surgeBps / 10000);
  const fraction = String(surgeBps % 10000).padStart(4, "0").replace(/0+$/, "");
  return `${whole}${fraction ? `,${fraction}` : ""}×`;
}
