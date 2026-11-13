export function safeReturnTo(value: unknown): string {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//") || value.includes("\\")) {
    return "/";
  }
  if (value === "/callback" || value.startsWith("/callback?") || value.startsWith("/callback/")) {
    return "/";
  }
  return value;
}

export function currentReturnTo(location: { pathname: string; search: string }): string {
  return safeReturnTo(location.pathname + location.search);
}
