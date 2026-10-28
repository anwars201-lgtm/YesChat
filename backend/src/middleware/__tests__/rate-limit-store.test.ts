import { describe, expect, it, beforeEach } from 'vitest';

// Inline-test the IncrExpireStore logic without booting the full Redis client.
// We mock only what the store actually needs: a `client.multi().call(...)` chain
// that returns a fake `exec()` result. This documents the wire-level contract
// (key prefixing, command order, EXPIRE NX semantics) which is what matters
// for the customer-Redis ACL compatibility (see ADR-0014).

type MultiExecRow = [Error | null, unknown];

/** Narrow `MULTI`/`.call()` chain surface used by IncrExpireStore (`ioredis` in prod). */
interface MultiBatch {
  call(...args: [string, ...unknown[]]): MultiBatch;
  exec(): Promise<MultiExecRow[] | null>;
}

interface IncrExpireStoreDeps {
  multi(): MultiBatch;
  call(...args: [string, ...unknown[]]): Promise<unknown>;
}

class FakeMulti implements MultiBatch {
  calls: Array<[string, ...unknown[]]> = [];
  private execResult: MultiExecRow[];

  constructor(execResult: MultiExecRow[]) {
    this.execResult = execResult;
  }

  call(...args: [string, ...unknown[]]): MultiBatch {
    this.calls.push(args);
    return this;
  }

  async exec(): Promise<MultiExecRow[] | null> {
    return this.execResult;
  }
}

class FakeClient implements IncrExpireStoreDeps {
  lastMulti: FakeMulti | null = null;
  rawCalls: Array<[string, ...unknown[]]> = [];
  private responses: MultiExecRow[][];

  constructor(responses: MultiExecRow[][]) {
    this.responses = responses;
  }

  multi(): MultiBatch {
    const next = this.responses.shift() ?? [];
    this.lastMulti = new FakeMulti(next);
    return this.lastMulti;
  }

  async call(...args: [string, ...unknown[]]): Promise<unknown> {
    this.rawCalls.push(args);
    return null;
  }
}

// Inline copy of the store implementation under test. Keep in sync with
// src/middleware/rateLimit.middleware.ts (extracting it would force a runtime
// import of ../rateLimit.middleware which calls getRedis() at module-load).
class IncrExpireStore {
  windowSec = 0;
  prefix: string;

  constructor(
    private client: IncrExpireStoreDeps,
    prefix: string,
  ) {
    this.prefix = prefix;
  }

  init(opts: { windowMs: number }): void {
    this.windowSec = Math.max(1, Math.ceil(opts.windowMs / 1000));
  }

  async increment(key: string): Promise<{ totalHits: number; resetTime: Date }> {
    const fullKey = `${this.prefix}${key}`;
    const result = (await this.client
      .multi()
      .call('INCR', fullKey)
      .call('EXPIRE', fullKey, String(this.windowSec), 'NX')
      .call('TTL', fullKey)
      .exec()) as MultiExecRow[] | null;

    if (!result || result.length < 3) {
      throw new Error('rate-limit MULTI returned null or short result (transaction aborted)');
    }
    const [[, hitsRaw], , [, ttlRaw]] = result;
    return {
      totalHits: Number(hitsRaw),
      resetTime: new Date(Date.now() + Math.max(0, Number(ttlRaw)) * 1000),
    };
  }

  async decrement(key: string): Promise<void> {
    await this.client.call('DECR', `${this.prefix}${key}`);
  }

  async resetKey(key: string): Promise<void> {
    await this.client.call('DEL', `${this.prefix}${key}`);
  }
}

const PREFIX = 'proj_abc123:global:';
const WINDOW_MS = 15 * 60 * 1000;
const WINDOW_SEC = 900;

describe('IncrExpireStore (Lua-free rate-limit)', () => {
  let store: IncrExpireStore;
  let client: FakeClient;

  beforeEach(() => {
    // Each test pre-loads the multi.exec results it expects in sequence
  });

  it('first request: total=1, TTL=window, EXPIRE NX is the second command', async () => {
    client = new FakeClient([
      [
        [null, 1], // INCR
        [null, 1], // EXPIRE NX (set)
        [null, WINDOW_SEC], // TTL
      ],
    ]);
    store = new IncrExpireStore(client, PREFIX);
    store.init({ windowMs: WINDOW_MS });

    const result = await store.increment('user:42');

    expect(result.totalHits).toBe(1);
    // Verify the wire-level commands and key shape
    expect(client.lastMulti?.calls).toEqual([
      ['INCR', 'proj_abc123:global:user:42'],
      ['EXPIRE', 'proj_abc123:global:user:42', String(WINDOW_SEC), 'NX'],
      ['TTL', 'proj_abc123:global:user:42'],
    ]);
  });

  it('second request inside window: total=2, EXPIRE NX is no-op (returns 0), TTL preserves remainder', async () => {
    client = new FakeClient([
      [
        [null, 2], // INCR
        [null, 0], // EXPIRE NX returned 0 (TTL already set) — atomic on server
        [null, 600], // TTL — 600s remaining
      ],
    ]);
    store = new IncrExpireStore(client, PREFIX);
    store.init({ windowMs: WINDOW_MS });

    const result = await store.increment('user:42');
    expect(result.totalHits).toBe(2);
    // resetTime should reflect the REMAINING TTL, not the full window
    const expectedReset = Date.now() + 600 * 1000;
    expect(Math.abs(result.resetTime.getTime() - expectedReset)).toBeLessThan(500);
  });

  it('after window expires: total=1 again, fresh TTL', async () => {
    client = new FakeClient([
      [
        [null, 1], // INCR — fresh counter
        [null, 1], // EXPIRE NX returned 1 (TTL set)
        [null, WINDOW_SEC], // TTL — full window
      ],
    ]);
    store = new IncrExpireStore(client, PREFIX);
    store.init({ windowMs: WINDOW_MS });

    const result = await store.increment('user:42');
    expect(result.totalHits).toBe(1);
  });

  it('decrement uses raw .call with full prefix', async () => {
    client = new FakeClient([]);
    store = new IncrExpireStore(client, PREFIX);
    store.init({ windowMs: WINDOW_MS });

    await store.decrement('user:42');
    expect(client.rawCalls).toEqual([['DECR', 'proj_abc123:global:user:42']]);
  });

  it('resetKey uses raw .call with full prefix', async () => {
    client = new FakeClient([]);
    store = new IncrExpireStore(client, PREFIX);
    store.init({ windowMs: WINDOW_MS });

    await store.resetKey('apikey:secret');
    expect(client.rawCalls).toEqual([['DEL', 'proj_abc123:global:apikey:secret']]);
  });

  it('throws on null exec result so passOnStoreError can release the request', async () => {
    client = new FakeClient([]);
    client.multi = () => {
      const m = new FakeMulti([]);
      m.exec = async (): Promise<MultiExecRow[] | null> => null;
      return m;
    };
    store = new IncrExpireStore(client, PREFIX);
    store.init({ windowMs: WINDOW_MS });

    await expect(store.increment('user:42')).rejects.toThrow(/MULTI returned null/);
  });

  it('windowMs<1000 still uses windowSec>=1 (avoids EXPIRE 0 bug)', () => {
    client = new FakeClient([]);
    store = new IncrExpireStore(client, PREFIX);
    store.init({ windowMs: 200 });
    expect(store.windowSec).toBe(1);
  });

  it('uses raw .call() throughout — never mixes auto-prefixed .incr() with raw .call()', async () => {
    // Regression guard: if someone refactors the store to use .multi().incr(),
    // ioredis would auto-apply keyPrefix while .call('EXPIRE') would not,
    // producing two different wire keys in the same MULTI block. The
    // counter would increment on one key while TTL was set on another —
    // counter never expires, customer perma-rate-limited at 100 requests.
    client = new FakeClient([
      [
        [null, 1],
        [null, 1],
        [null, WINDOW_SEC],
      ],
    ]);
    store = new IncrExpireStore(client, PREFIX);
    store.init({ windowMs: WINDOW_MS });
    await store.increment('user:42');

    // All three commands must reference the EXACT same fully-prefixed key
    const keys = client.lastMulti?.calls.map((c) => c[1]);
    expect(keys).toEqual(['proj_abc123:global:user:42', 'proj_abc123:global:user:42', 'proj_abc123:global:user:42']);
  });
});
