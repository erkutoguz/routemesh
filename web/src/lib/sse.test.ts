import { describe, expect, it } from "vitest";
import { SseParser, runSse, type SseMessage } from "./sse";

describe("SseParser", () => {
  it("parses named events with data and id", () => {
    const parser = new SseParser();
    const out = parser.feed('id: 1:2\nevent: location\ndata: {"a":1}\n\n');
    expect(out).toEqual([{ event: "location", data: '{"a":1}', id: "1:2" }]);
  });

  it("ignores comments such as heartbeats", () => {
    const parser = new SseParser();
    expect(parser.feed(": connected\n\n: heartbeat\n\n")).toEqual([]);
  });

  it("joins multi-line data and defaults the event name", () => {
    const parser = new SseParser();
    expect(parser.feed("data: a\ndata: b\n\n")).toEqual([{ event: "message", data: "a\nb", id: null }]);
  });

  it("handles chunks split mid-line and mid-terminator", () => {
    const parser = new SseParser();
    expect(parser.feed("event: appro")).toEqual([]);
    expect(parser.feed("aching\r\ndata: {\"k\"")).toEqual([]);
    expect(parser.feed(":1}\r")).toEqual([]);
    expect(parser.feed("\n\r\n")).toEqual([{ event: "approaching", data: '{"k":1}', id: null }]);
  });

  it("emits several events from one chunk", () => {
    const parser = new SseParser();
    const out = parser.feed("event: a\ndata: 1\n\nevent: b\ndata: 2\n\n");
    expect(out.map((m) => m.event)).toEqual(["a", "b"]);
  });

  it("accepts data without a space after the colon", () => {
    const parser = new SseParser();
    expect(parser.feed("data:first\n\n")[0].data).toBe("first");
  });
});

function streamResponse(...chunks: string[]): Response {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream({
      start(controller) {
        chunks.forEach((c) => controller.enqueue(encoder.encode(c)));
        controller.close();
      },
    }),
  );
}

describe("runSse", () => {
  it("delivers messages and stops after a terminal event", async () => {
    const seen: SseMessage[] = [];
    let opens = 0;
    await runSse({
      open: async () => {
        opens++;
        return streamResponse("event: location\ndata: 1\n\n", 'event: end\ndata: {"reason":"ENDED"}\n\n');
      },
      onMessage: (m) => seen.push(m),
      isTerminal: (m) => m.event === "end",
      signal: new AbortController().signal,
      sleep: async () => {},
    });
    expect(seen.map((m) => m.event)).toEqual(["location", "end"]);
    expect(opens).toBe(1);
  });

  it("reconnects with backoff after the stream drops or fails", async () => {
    const delays: number[] = [];
    const statuses: string[] = [];
    let opens = 0;
    await runSse({
      open: async () => {
        opens++;
        if (opens === 1) {
          throw new Error("network");
        }
        if (opens === 2) {
          return streamResponse("event: location\ndata: 1\n\n");
        }
        return streamResponse("event: end\ndata: {}\n\n");
      },
      onMessage: () => {},
      onStatus: (s) => statuses.push(s),
      isTerminal: (m) => m.event === "end",
      signal: new AbortController().signal,
      baseDelayMs: 100,
      sleep: async (ms) => {
        delays.push(ms);
      },
    });
    expect(opens).toBe(3);
    expect(delays).toEqual([100, 100]);
    expect(statuses).toContain("reconnecting");
    expect(statuses.at(-1)).toBe("closed");
  });

  it("stops when aborted", async () => {
    const controller = new AbortController();
    let opens = 0;
    await runSse({
      open: async () => {
        opens++;
        controller.abort();
        throw new Error("aborted");
      },
      onMessage: () => {},
      signal: controller.signal,
      sleep: async () => {},
    });
    expect(opens).toBe(1);
  });
});
