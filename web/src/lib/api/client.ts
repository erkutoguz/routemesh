import { API_URL } from "../config";

export interface Problem {
  status: number;
  title?: string;
  detail?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${problem.status}`);
    this.status = problem.status;
    this.problem = problem;
  }
}

export interface RequestOptions {
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export interface ApiClientDeps {
  getAccessToken: () => Promise<string | null>;
  refresh: () => Promise<string | null>;
  baseUrl?: string;
  fetchImpl?: typeof fetch;
}

export interface ApiClient {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>;
  post: <T>(path: string, options?: RequestOptions) => Promise<T>;
  put: <T>(path: string, options?: RequestOptions) => Promise<T>;
  stream: (path: string, signal: AbortSignal) => Promise<Response>;
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export function createApiClient(deps: ApiClientDeps): ApiClient {
  const baseUrl = deps.baseUrl ?? API_URL;
  const doFetch = deps.fetchImpl ?? ((input, init) => fetch(input, init));

  async function send(method: string, path: string, options: RequestOptions, accept?: string): Promise<Response> {
    const attempt = async (token: string | null) => {
      const headers = new Headers({ Accept: accept ?? "application/json" });
      if (token) {
        headers.set("Authorization", `Bearer ${token}`);
      }
      if (options.idempotencyKey) {
        headers.set("Idempotency-Key", options.idempotencyKey);
      }
      let body: BodyInit | undefined;
      if (options.body instanceof FormData) {
        body = options.body;
      } else if (options.body !== undefined) {
        headers.set("Content-Type", "application/json");
        body = JSON.stringify(options.body);
      }
      return doFetch(`${baseUrl}${path}`, { method, headers, body, signal: options.signal });
    };

    let response = await attempt(await deps.getAccessToken());
    if (response.status === 401) {
      const renewed = await deps.refresh();
      if (renewed) {
        response = await attempt(renewed);
      }
    }
    return response;
  }

  async function parseProblem(response: Response): Promise<ApiError> {
    try {
      const json = (await response.json()) as Partial<Problem>;
      return new ApiError({ ...json, status: response.status });
    } catch {
      return new ApiError({ status: response.status, title: response.statusText });
    }
  }

  async function json<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
    const response = await send(method, path, options);
    if (!response.ok) {
      throw await parseProblem(response);
    }
    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  return {
    get: (path, options) => json("GET", path, options),
    post: (path, options) => json("POST", path, options),
    put: (path, options) => json("PUT", path, options),
    stream: async (path, signal) => {
      const response = await send("GET", path, { signal }, "text/event-stream");
      if (!response.ok) {
        throw await parseProblem(response);
      }
      return response;
    },
  };
}
