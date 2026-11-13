export interface SseMessage {
  event: string;
  data: string;
  id: string | null;
}

export class SseParser {
  private buffer = "";
  private event = "";
  private data: string[] = [];
  private id: string | null = null;

  feed(chunk: string): SseMessage[] {
    this.buffer += chunk;
    const messages: SseMessage[] = [];
    let start = 0;
    for (let i = 0; i < this.buffer.length; i++) {
      const ch = this.buffer[i];
      if (ch !== "\n" && ch !== "\r") {
        continue;
      }
      if (ch === "\r" && i === this.buffer.length - 1) {
        break;
      }
      const line = this.buffer.slice(start, i);
      if (ch === "\r" && this.buffer[i + 1] === "\n") {
        i++;
      }
      start = i + 1;
      const message = this.line(line);
      if (message) {
        messages.push(message);
      }
    }
    this.buffer = this.buffer.slice(start);
    return messages;
  }

  private line(line: string): SseMessage | null {
    if (line === "") {
      if (this.data.length === 0 && this.event === "") {
        return null;
      }
      const message = { event: this.event || "message", data: this.data.join("\n"), id: this.id };
      this.event = "";
      this.data = [];
      return message;
    }
    if (line.startsWith(":")) {
      return null;
    }
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) {
      value = value.slice(1);
    }
    if (field === "event") {
      this.event = value;
    } else if (field === "data") {
      this.data.push(value);
    } else if (field === "id") {
      this.id = value;
    }
    return null;
  }
}

export interface SseOptions {
  open: (signal: AbortSignal) => Promise<Response>;
  onMessage: (message: SseMessage) => void;
  onStatus?: (status: "connecting" | "open" | "reconnecting" | "closed") => void;
  signal: AbortSignal;
  isTerminal?: (message: SseMessage) => boolean;
  baseDelayMs?: number;
  maxDelayMs?: number;
  sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
}

function defaultSleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        resolve();
      },
      { once: true },
    );
  });
}

export async function runSse(options: SseOptions): Promise<void> {
  const { open, onMessage, onStatus, signal } = options;
  const base = options.baseDelayMs ?? 1000;
  const max = options.maxDelayMs ?? 15000;
  const sleep = options.sleep ?? defaultSleep;
  let failures = 0;

  while (!signal.aborted) {
    onStatus?.(failures === 0 ? "connecting" : "reconnecting");
    let finished = false;
    try {
      const response = await open(signal);
      if (!response.body) {
        throw new Error("stream has no body");
      }
      onStatus?.("open");
      failures = 0;
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
      const parser = new SseParser();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        for (const message of parser.feed(value)) {
          onMessage(message);
          if (options.isTerminal?.(message)) {
            finished = true;
          }
        }
        if (finished) {
          await reader.cancel();
          break;
        }
      }
    } catch {
      if (signal.aborted) {
        break;
      }
      failures++;
    }
    if (finished || signal.aborted) {
      break;
    }
    if (failures === 0) {
      failures = 1;
    }
    await sleep(Math.min(max, base * 2 ** (failures - 1)), signal);
  }
  onStatus?.("closed");
}
