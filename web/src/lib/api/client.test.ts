import { describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient } from "./client";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function setup(responses: Response[]) {
  const fetchImpl = vi.fn<typeof fetch>();
  responses.forEach((r) => fetchImpl.mockResolvedValueOnce(r));
  const refresh = vi.fn<() => Promise<string | null>>().mockResolvedValue("fresh");
  const client = createApiClient({
    getAccessToken: async () => "tok",
    refresh,
    baseUrl: "http://gw",
    fetchImpl,
  });
  return { client, fetchImpl, refresh };
}

function headersOf(fetchImpl: ReturnType<typeof vi.fn<typeof fetch>>, call: number): Headers {
  return fetchImpl.mock.calls[call][1]!.headers as Headers;
}

describe("api client", () => {
  it("sends bearer token, idempotency key and json body", async () => {
    const { client, fetchImpl } = setup([jsonResponse(200, { ok: true })]);

    const result = await client.post<{ ok: boolean }>("/api/v1/quotes", { body: { a: 1 }, idempotencyKey: "k-1" });

    expect(result).toEqual({ ok: true });
    const [url, init] = fetchImpl.mock.calls[0];
    expect(url).toBe("http://gw/api/v1/quotes");
    expect(init!.method).toBe("POST");
    expect(init!.body).toBe('{"a":1}');
    const headers = headersOf(fetchImpl, 0);
    expect(headers.get("Authorization")).toBe("Bearer tok");
    expect(headers.get("Idempotency-Key")).toBe("k-1");
    expect(headers.get("Content-Type")).toBe("application/json");
  });

  it("maps problem details to ApiError", async () => {
    const { client } = setup([jsonResponse(409, { title: "Conflict", detail: "already used" })]);

    const error = await client.get("/x").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).message).toBe("already used");
  });

  it("falls back to the status text when the error body is not json", async () => {
    const { client } = setup([new Response("boom", { status: 502, statusText: "Bad Gateway" })]);

    const error = (await client.get("/x").catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(502);
    expect(error.problem.title).toBe("Bad Gateway");
  });

  it("refreshes once on 401 and retries with the new token", async () => {
    const { client, fetchImpl, refresh } = setup([jsonResponse(401, {}), jsonResponse(200, { n: 1 })]);

    expect(await client.get("/x")).toEqual({ n: 1 });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(headersOf(fetchImpl, 1).get("Authorization")).toBe("Bearer fresh");
  });

  it("surfaces 401 when the refresh fails", async () => {
    const { client, refresh } = setup([jsonResponse(401, {})]);
    refresh.mockResolvedValueOnce(null);

    const error = (await client.get("/x").catch((e: unknown) => e)) as ApiError;

    expect(error.status).toBe(401);
  });

  it("does not set a json content type for form data", async () => {
    const { client, fetchImpl } = setup([jsonResponse(201, {})]);

    await client.post("/upload", { body: new FormData() });

    expect(headersOf(fetchImpl, 0).get("Content-Type")).toBeNull();
  });
});
