import { ApiError } from "@/lib/api/client";

export function ErrorBanner({ error }: { error: unknown }) {
  if (!error) {
    return null;
  }
  const message =
    error instanceof ApiError
      ? `${error.status}${error.problem.title ? ` ${error.problem.title}` : ""}: ${error.message}`
      : error instanceof Error
        ? error.message
        : "Something went wrong";
  return (
    <p role="alert" className="rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
      {message}
    </p>
  );
}
